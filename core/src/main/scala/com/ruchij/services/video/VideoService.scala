package com.ruchij.services.video

import java.nio.file.Path

import com.ruchij.daos.video.models.Video
import com.ruchij.daos.videometadata.models.VideoMetadata

trait VideoService[F[_]] {
  def insert(videoMetadata: VideoMetadata, path: Path): F[Video]
}
