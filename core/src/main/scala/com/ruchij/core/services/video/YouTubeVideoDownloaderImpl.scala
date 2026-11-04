package com.ruchij.core.services.video

import cats.{Applicative, MonadThrow}
import cats.data.{Kleisli, OptionT}
import cats.effect.{Async, Deferred, Ref, Sync}
import cats.implicits._
import com.ruchij.core.config.HttpProxyConfiguration
import com.ruchij.core.daos.videometadata.models.{VideoSite, WebPage}
import com.ruchij.core.exceptions.{CliCommandException, ResourceNotFoundException}
import com.ruchij.core.logging.Logger
import com.ruchij.core.services.cli.CliCommandRunner
import com.ruchij.core.services.video.YouTubeVideoDownloaderImpl.{
  PostProcessingTimeout,
  SilenceTimeout,
  isPostProcessing
}
import com.ruchij.core.services.video.models.YTDataSize.ytDataSizeOrdering
import com.ruchij.core.services.video.models._
import com.ruchij.core.types.FunctionKTypes._
import com.ruchij.core.utils.{JsoupSelector, Timers}
import fs2.Stream
import io.circe.generic.auto._
import io.circe.{parser => JsonParser}
import org.http4s.Uri
import org.http4s.circe.decodeUri
import org.http4s.client.Client
import org.http4s.implicits.http4sLiteralsSyntax
import org.jsoup.Jsoup

import java.util.concurrent.{TimeUnit, TimeoutException}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps
import scala.math.Ordered.orderingToOrdered
import scala.util.matching.Regex

class YouTubeVideoDownloaderImpl[F[_]: Async](
  cliCommandRunner: CliCommandRunner[F],
  client: Client[F],
  maybeHttpProxyConfiguration: Option[HttpProxyConfiguration]
) extends YouTubeVideoDownloader[F] {

  private val logger = Logger[YouTubeVideoDownloaderImpl[F]]

  private def ytDlp: List[String] =
    List("yt-dlp", "--no-warnings") ++
      maybeHttpProxyConfiguration.fold[List[String]](List.empty) {
        httpProxy => List("--proxy", httpProxy.proxyUrl.renderString)
      }

  override def videoInformation(uri: Uri): F[VideoAnalysisResult] =
    cliCommandRunner
      .run(ytDlp ++ List(uri.renderString, "-j"))
      .compile
      .string
      .recoverWith {
        case cliCommandException: CliCommandException =>
          val errorMessage = cliCommandException.error

          if (errorMessage.contains("HTTP Error 404: Not Found")) {
            MonadThrow[F].raiseError {
              ResourceNotFoundException(s"Unable to find the video resource at ${uri.renderString}")
            }
          } else if (errorMessage.contains("Unable to extract hash;")) {
            MonadThrow[F].raiseError {
              ResourceNotFoundException(s"Video seems to have been deleted at ${uri.renderString}")
            }
          } else {
            MonadThrow[F].raiseError(cliCommandException)
          }
      }
      .flatMap { output =>
        JsonParser.decode[YTDownloaderMetadata](output).toType[F, Throwable]
      }
      .flatMap { metadata =>
        VideoSite
          .fromUri(uri)
          .toType[F, Throwable]
          .product {
            OptionT
              .fromOption[F](metadata.thumbnail)
              .getOrElseF {
                MonadThrow[F].handleError(retrieveThumbnailFromUri.run(uri)) { _ =>
                  uri"https://s3.ap-southeast-2.amazonaws.com/assets.video-downloader.ruchij.com/video-placeholder.png"
                }
              }
          }
          .flatMap {
            case (videoSite, thumbnail) =>
              MonadThrow[F]
                .catchNonFatal(Math.floor(metadata.duration).toInt)
                .map { seconds =>
                  VideoAnalysisResult(
                    uri,
                    videoSite,
                    metadata.title,
                    FiniteDuration(seconds, TimeUnit.SECONDS),
                    metadata.formats.flatMap(_.filesize.map(_.toLong)).maxOption.getOrElse[Long](0),
                    thumbnail
                  )
                }
          }
      }

  private val retrieveThumbnailFromUri: Kleisli[F, Uri, Uri] =
    JsoupSelector
      .singleElement[F]("video")
      .flatMapF(videoElement => JsoupSelector.attribute[F](videoElement, "poster"))
      .flatMapF(property => Uri.fromString(property).toType[F, Throwable])
      .compose[Uri, WebPage] { uri: Uri =>
        client
          .expect[String](uri)
          .flatMap(html => Sync[F].catchNonFatal(Jsoup.parse(html)))
          .map(document => WebPage(uri, document))
      }

  override val supportedSites: F[Seq[String]] =
    Sync[F].defer {
      cliCommandRunner
        .run(ytDlp :+ "--list-extractors")
        .compile
        .toVector
        .widen[Seq[String]]
    }

  override val version: F[String] =
    Sync[F].defer { cliCommandRunner.run(ytDlp :+ "--version").compile.string }

  override def downloadVideo(uri: Uri, pathWithoutExtension: String): Stream[F, YTDownloaderProgress] =
    Stream.eval(Ref.of[F, Boolean](false).product(Deferred[F, Unit])).flatMap {
      case (ref, postProcessingStarted) =>
        cliCommandRunner
          // yt-dlp skips fixups for a file it finds already downloaded, unless forced. A download that was interrupted
          // during a fixup would otherwise be accepted on its retry in its unfixed form, such as MPEG-TS in an mp4 file.
          .run(ytDlp ++ List("--fixup", "force", "-o", s"$pathWithoutExtension.%(ext)s", uri.renderString))
          .interruptWhen {
            downloadTimeout(ref, postProcessingStarted)
              .flatTap {
                case Left(timeoutException) =>
                  logger.error[F](s"YoutubeDownloader timed out for url=${uri.renderString}", timeoutException)

                case Right(_) => Applicative[F].unit
              }
          }
          .evalTap { line =>
            ref.set(true).productR {
              postProcessingStarted.complete((): Unit).void.whenA(isPostProcessing(line))
            }
          }
          .collect {
            case YTDownloaderProgress(progress) => progress
          }
          .scan(
            YTDownloaderProgress(
              0,
              YTDataSize(0, YTDataUnit.MiB),
              YTDataSize(0, YTDataUnit.MiB),
              FiniteDuration(0, TimeUnit.SECONDS)
            )
          ) { (result, current) =>
            if (current.totalSize >= result.totalSize) current else result
          }
    }

  /**
    * yt-dlp prints nothing while ffmpeg post-processes a file, which takes minutes for a large video. Silence only
    * means a stalled download until post-processing starts, after which the whole step gets a single time limit.
    */
  private def downloadTimeout(
    resetSignal: Ref[F, Boolean],
    postProcessingStarted: Deferred[F, Unit]
  ): F[Either[Throwable, Unit]] =
    Async[F].race(Timers.createResettableTimer(SilenceTimeout, resetSignal), postProcessingStarted.get).flatMap {
      case Left(result) => Applicative[F].pure(result)

      case Right(_) =>
        Async[F].sleep(PostProcessingTimeout).as {
          Left(new TimeoutException(s"Post-processing did not finish within $PostProcessingTimeout"))
        }
    }
}

object YouTubeVideoDownloaderImpl {
  private val SilenceTimeout: FiniteDuration = 30 seconds

  private val PostProcessingTimeout: FiniteDuration = 30 minutes

  private val PostProcessingLine: Regex = "^\\[(Fixup\\w+|Merger|ffmpeg)\\].*".r

  private def isPostProcessing(line: String): Boolean = PostProcessingLine.matches(line)
}
