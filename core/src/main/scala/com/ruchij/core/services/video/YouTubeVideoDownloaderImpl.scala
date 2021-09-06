package com.ruchij.core.services.video

import cats.data.{Kleisli, OptionT}
import cats.effect.{Async, Bracket, Sync}
import cats.implicits._
import com.ruchij.core.daos.videometadata.models.VideoSite
import com.ruchij.core.services.cli.CliCommandRunner
import com.ruchij.core.services.video.models.{VideoAnalysisResult, YTDownloaderMetadata, YTDownloaderProgress}
import com.ruchij.core.types.FunctionKTypes._
import com.ruchij.core.utils.JsoupSelector
import fs2.Stream
import io.circe.{parser => JsonParser}
import io.circe.generic.auto._
import org.http4s.Uri
import org.http4s.circe.decodeUri
import org.http4s.client.Client
import org.http4s.implicits.http4sLiteralsSyntax
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

class YouTubeVideoDownloaderImpl[F[_]: Async](cliCommandRunner: CliCommandRunner[F], client: Client[F]) extends YouTubeVideoDownloader[F] {

  override def videoInformation(uri: Uri): F[VideoAnalysisResult] =
    cliCommandRunner
      .run(s"""youtube-dl "${uri.renderString}" -j""")
      .compile
      .string
      .flatMap(output => JsonParser.decode[YTDownloaderMetadata](output).toType[F, Throwable])
      .flatMap { metadata =>
        VideoSite.fromUri(uri).toType[F, Throwable]
          .product {
            OptionT.fromOption[F](metadata.thumbnail)
              .getOrElseF {
                Bracket[F, Throwable].handleError(retrieveThumbnailFromUri.run(uri)) {_ =>
                  uri"https://s3.ap-southeast-2.amazonaws.com/assets.video-downloader.ruchij.com/video-placeholder.png"
                }
              }
          }
          .map {
            case (videoSite, thumbnail) =>
              VideoAnalysisResult(
                uri,
                videoSite,
                metadata.title,
                FiniteDuration(metadata.duration, TimeUnit.SECONDS),
                metadata.formats.flatMap(_.filesize.map(_.toLong)).maxOption.getOrElse[Long](0),
                thumbnail
              )
          }
      }

  private val retrieveThumbnailFromUri: Kleisli[F, Uri, Uri] =
    JsoupSelector.singleElement[F]("video")
      .flatMapF(videoElement => JsoupSelector.attribute[F](videoElement, "poster"))
      .flatMapF(property => Uri.fromString(property).toType[F, Throwable])
      .compose[Uri, Document] {
        uri: Uri =>
          client.expect[String](uri)
            .flatMap(html => Sync[F].catchNonFatal(Jsoup.parse(html)))
      }

  override val supportedSites: F[Seq[String]] =
    Sync[F].defer {
      cliCommandRunner.run("youtube-dl --list-extractors")
        .compile
        .toVector
        .map(identity[Seq[String]])
    }

  override def downloadVideo(uri: Uri, pathWithoutExtension: String): Stream[F, Long] =
    cliCommandRunner
      .run(s"""youtube-dl -o "$pathWithoutExtension.%(ext)s" "${uri.renderString}"""")
      .collect {
        case YTDownloaderProgress(progress) => math.round(progress.completed / 100 * progress.totalSize.bytes)
      }
      .scan[Long](0) {
        (result, current) => math.max(result, current)
      }

}
