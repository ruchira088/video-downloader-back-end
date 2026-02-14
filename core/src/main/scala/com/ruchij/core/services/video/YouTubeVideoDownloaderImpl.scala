package com.ruchij.core.services.video

import cats.data.{Kleisli, OptionT}
import cats.effect.{Async, Ref, Sync}
import cats.MonadThrow
import cats.implicits._
import com.ruchij.core.daos.videometadata.models.{VideoSite, WebPage}
import com.ruchij.core.exceptions.{CliCommandException, ResourceNotFoundException}
import com.ruchij.core.logging.Logger
import com.ruchij.core.services.cli.CliCommandRunner
import com.ruchij.core.services.video.models.{VideoAnalysisResult, YTDataSize, YTDataUnit, YTDownloaderMetadata, YTDownloaderProgress}
import com.ruchij.core.types.FunctionKTypes._
import com.ruchij.core.services.video.models.YTDataSize.ytDataSizeOrdering
import com.ruchij.core.utils.{JsoupSelector, Timers}
import fs2.Stream
import io.circe.{parser => JsonParser}
import io.circe.generic.auto._
import org.http4s.Uri
import org.http4s.circe.decodeUri
import org.http4s.client.Client
import org.http4s.implicits.http4sLiteralsSyntax
import org.jsoup.Jsoup

import java.util.concurrent.TimeUnit
import scala.concurrent.TimeoutException
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps
import scala.math.Ordered.orderingToOrdered

class YouTubeVideoDownloaderImpl[F[_]: Async](cliCommandRunner: CliCommandRunner[F], client: Client[F])
    extends YouTubeVideoDownloader[F] {

  private val logger = Logger[YouTubeVideoDownloaderImpl[F]]

  override def videoInformation(uri: Uri): F[VideoAnalysisResult] =
    cliCommandRunner
      .run(s"""yt-dlp --no-warnings "${uri.renderString}" -j""")
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
        .run("yt-dlp --no-warnings --list-extractors")
        .compile
        .toVector
        .widen[Seq[String]]
    }

  override val version: F[String] =
    Sync[F].defer { cliCommandRunner.run("yt-dlp --version").compile.string }

  override def downloadVideo(uri: Uri, pathWithoutExtension: String): Stream[F, YTDownloaderProgress] =
    Stream.eval(Ref.of[F, Boolean](false)).flatMap { ref =>
      cliCommandRunner
        .run(s"""yt-dlp --no-warnings -o "$pathWithoutExtension.%(ext)s" "${uri.renderString}"""")
        .interruptWhen {
          Timers
            .createResettableTimer(30 seconds, ref)
            .recoverWith {
              case timeoutException: TimeoutException =>
                logger
                  .error[F](s"YoutubeDownloader failed download any data for url=${uri.renderString}", timeoutException)
                  .as(Left(timeoutException))
            }
        }
        .collect {
          case YTDownloaderProgress(progress) => progress
        }
        .evalTap { _ =>
          ref.set(true)
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
}
