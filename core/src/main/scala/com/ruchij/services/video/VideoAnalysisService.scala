package com.ruchij.services.video

import com.ruchij.daos.videometadata.models.VideoMetadata
import org.http4s.Uri

trait VideoAnalysisService[F[_]] {
  def metadata(uri: Uri): F[VideoMetadata]

  def downloadUri(uri: Uri): F[Uri]
}
