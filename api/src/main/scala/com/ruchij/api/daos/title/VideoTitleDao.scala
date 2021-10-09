package com.ruchij.api.daos.title

import com.ruchij.api.daos.title.models.VideoTitle

trait VideoTitleDao[F[_]] {
  def insert(videoTitle: VideoTitle): F[Int]

  def find(videoId: String, userId: String): F[Option[VideoTitle]]

  def update(videoId: String, userId: String, title: String): F[Int]

  def delete(maybeVideoId: Option[String], maybeUserId: Option[String]): F[Int]
}
