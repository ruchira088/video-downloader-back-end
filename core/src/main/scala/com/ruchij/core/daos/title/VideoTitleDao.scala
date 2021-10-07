package com.ruchij.core.daos.title

import com.ruchij.core.daos.title.models.VideoTitle

trait VideoTitleDao[F[_]] {
  def insert(videoTitle: VideoTitle): F[Int]

  def find(videoId: String, userId: String): F[Option[VideoTitle]]

  def update(videoId: String, userId: String, title: String): F[Int]

  def deleteByUserId(userId: String): F[Int]
}
