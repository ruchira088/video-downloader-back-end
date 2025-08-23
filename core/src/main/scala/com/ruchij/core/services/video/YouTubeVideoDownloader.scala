package com.ruchij.core.services.video

import com.ruchij.core.services.video.models.{VideoAnalysisResult, YTDownloaderProgress}
import fs2.Stream
import org.http4s.Uri

trait YouTubeVideoDownloader[F[_]] {
  def videoInformation(uri: Uri): F[VideoAnalysisResult]

  def downloadVideo(uri: Uri, pathWithoutExtension: String): Stream[F, YTDownloaderProgress]

  val supportedSites: F[Seq[String]]

  val version: F[String]
}