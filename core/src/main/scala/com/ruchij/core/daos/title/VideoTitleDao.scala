package com.ruchij.core.daos.title

import com.ruchij.core.daos.title.models.VideoTitle

trait VideoTitleDao[F[_]] {
  /** Returns 0 if a title already exists for the video and user */
  def insert(videoTitle: VideoTitle): F[Int]

  def find(videoId: String, userId: String): F[Option[VideoTitle]]

  def update(videoId: String, userId: String, title: String): F[Int]

  def delete(maybeVideoId: Option[String], maybeUserId: Option[String]): F[Int]
}
