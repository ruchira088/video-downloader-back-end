package com.ruchij.services.video

import com.ruchij.daos.video.models.VideoMetadata
import org.http4s.Uri

trait VideoService[F[_]] {
  def metadata(uri: Uri): F[VideoMetadata]
}
