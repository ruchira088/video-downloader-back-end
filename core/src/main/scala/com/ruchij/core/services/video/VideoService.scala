package com.ruchij.core.services.video

import com.ruchij.core.daos.video.models.Video

trait VideoService[F[_], G[_]] {
  def findVideoById(videoId: String, maybeUserId: Option[String]): G[Video]

  def deleteById(videoId: String, deleteVideoFile: Boolean): F[Video]

  val queueIncorrectlyCompletedVideos: F[Seq[Video]]
}
