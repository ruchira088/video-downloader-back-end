package com.ruchij.core.daos.video.models

import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.videometadata.models.VideoMetadata

import scala.concurrent.duration.FiniteDuration

final case class Video(videoMetadata: VideoMetadata, fileResource: FileResource, watchTime: FiniteDuration) {
  val id: String = videoMetadata.id
}
