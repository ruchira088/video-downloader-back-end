package com.ruchij.core.services.video

import com.ruchij.core.services.video.models.VideoAnalysisResult
import org.http4s.Uri

trait VideoAnalysisService[F[_]] {
  def metadata(uri: Uri): F[VideoAnalysisResult]

  def downloadUri(uri: Uri): F[Uri]
}
