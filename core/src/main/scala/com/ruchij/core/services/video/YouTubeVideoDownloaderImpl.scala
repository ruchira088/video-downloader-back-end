package com.ruchij.core.services.video

import cats.effect.Async
import cats.implicits._
import com.ruchij.core.daos.videometadata.models.VideoSite
import com.ruchij.core.services.cli.CliCommandRunner
import com.ruchij.core.services.video.models.{VideoAnalysisResult, YTVideoDownloaderMetadata}
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
      .run(s"youtube-dl ${uri.renderString} -j", Stream.never[F])
      .compile
      .string
      .flatMap(output => JsonParser.decode[YTVideoDownloaderMetadata](output).toType[F, Throwable])
      .map { metadata =>
        VideoAnalysisResult(
          uri,
          VideoSite.Local,
          metadata.title,
          FiniteDuration(metadata.duration, TimeUnit.SECONDS),
          metadata.formats.flatMap(_.filesize.map(_.toLong)).maxOption.getOrElse[Long](0),
          metadata.thumbnail
        )
      }

  override def supportedSites: F[Seq[String]] =
    cliCommandRunner.run("youtube-dl --list-extractors", Stream.never[F])
      .compile
      .toVector
      .map(identity[Seq[String]])
}
