package com.ruchij.core.services.video

import com.ruchij.core.daos.videometadata.models.VideoMetadata
import com.ruchij.core.services.video.VideoAnalysisService.VideoMetadataResult
import com.ruchij.core.services.video.models.VideoAnalysisResult
import org.http4s.Uri

import scala.concurrent.duration.FiniteDuration

trait VideoAnalysisService[F[_]] {
  def metadata(uri: Uri): F[VideoMetadataResult]

  def analyze(uri: Uri): F[VideoAnalysisResult]

  def downloadUri(uri: Uri): F[Uri]

  def videoDurationFromPath(videoPath: String): F[FiniteDuration]
}

object VideoAnalysisService {
  sealed trait VideoMetadataResult {
    val value: VideoMetadata
  }

  final case class Existing(value: VideoMetadata) extends VideoMetadataResult
  final case class NewlyCreated(value: VideoMetadata) extends VideoMetadataResult
}
