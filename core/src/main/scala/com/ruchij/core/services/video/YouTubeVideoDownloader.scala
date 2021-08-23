package com.ruchij.core.services.video

import com.ruchij.core.services.video.models.VideoAnalysisResult
import org.http4s.Uri

trait YouTubeVideoDownloader[F[_]] {
  def videoInformation(uri: Uri): F[VideoAnalysisResult]

  def supportedSites: F[Seq[String]]

}