package com.ruchij.core.services.video

import com.ruchij.core.daos.videometadata.models.VideoMetadata
import com.ruchij.core.services.video.VideoAnalysisService.VideoMetadataResult
import org.http4s.Uri

trait VideoAnalysisService[F[_]] {
  def metadata(uri: Uri): F[VideoMetadataResult]

  def downloadUri(uri: Uri): F[Uri]
}

object VideoAnalysisService {
  sealed trait VideoMetadataResult {
    val value: VideoMetadata
  }

  case class Existing(value: VideoMetadata) extends VideoMetadataResult
  case class NewlyCreated(value: VideoMetadata) extends VideoMetadataResult
}
