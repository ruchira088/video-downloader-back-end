package com.ruchij.services.video

import com.ruchij.services.video.models.VideoAnalysisResult
import org.http4s.Uri

trait VideoAnalysisService[F[_]] {
  def metadata(uri: Uri): F[VideoAnalysisResult]

  def downloadUri(uri: Uri): F[Uri]
}
