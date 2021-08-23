package com.ruchij.core.services.video

import com.ruchij.core.services.video.models.VideoAnalysisResult
import fs2.Stream
import org.http4s.Uri

import java.nio.file.Path

trait YouTubeVideoDownloader[F[_]] {
  def videoInformation(uri: Uri): F[VideoAnalysisResult]

  def supportedSites: F[Seq[String]]

  def downloadVideo(uri: Uri, filePath: Path, interrupt: Stream[F, Boolean]): Stream[F, Long]
}