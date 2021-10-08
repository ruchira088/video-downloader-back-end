package com.ruchij.batch.services.video

import com.ruchij.core.daos.video.models.Video

import scala.concurrent.duration.FiniteDuration

trait BatchVideoService[F[_]] {
  def insert(videoMetadataKey: String, fileResourceKey: String): F[Video]

  def incrementWatchTime(videoId: String, duration: FiniteDuration): F[FiniteDuration]

  def fetchByVideoFileResourceId(videoFileResourceId: String): F[Video]

  def update(videoId: String, size: Long): F[Video]

  def deleteById(videoId: String, deleteVideoFile: Boolean): F[Video]
}
