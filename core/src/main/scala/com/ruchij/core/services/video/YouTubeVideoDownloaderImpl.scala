package com.ruchij.core.services.video

import cats.effect.{Async, Sync}
import cats.implicits._
import com.ruchij.core.daos.videometadata.models.VideoSite
import com.ruchij.core.services.cli.CliCommandRunner
import com.ruchij.core.services.video.models.{VideoAnalysisResult, YTDownloaderMetadata, YTDownloaderProgress}
import com.ruchij.core.types.FunctionKTypes._
import fs2.Stream
import io.circe.{parser => JsonParser}
import io.circe.generic.auto._
import org.http4s.Uri
import org.http4s.circe.decodeUri

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

class YouTubeVideoDownloaderImpl[F[_]: Async](cliCommandRunner: CliCommandRunner[F]) extends YouTubeVideoDownloader[F] {

  override def videoInformation(uri: Uri): F[VideoAnalysisResult] =
    cliCommandRunner
      .run(s"""youtube-dl "${uri.renderString}" -j""")
      .compile
      .string
      .flatMap(output => JsonParser.decode[YTDownloaderMetadata](output).toType[F, Throwable])
      .flatMap { metadata =>
        VideoSite.fromUri(uri).toType[F, Throwable]
          .map {
            videoSite =>
            VideoAnalysisResult(
              uri,
              videoSite,
              metadata.title,
              FiniteDuration(metadata.duration, TimeUnit.SECONDS),
              metadata.formats.flatMap(_.filesize.map(_.toLong)).maxOption.getOrElse[Long](0),
              metadata.thumbnail
            )
          }

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
