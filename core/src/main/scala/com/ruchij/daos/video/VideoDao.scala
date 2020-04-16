package com.ruchij.daos.video

import com.ruchij.daos.video.models.Video

trait VideoDao[F[_]] {
  def insert(video: Video): F[Int]

  def search(term: Option[String], pageNumber: Int, pageSize: Int): F[Seq[Video]]

  def findByKey(key: String): F[Option[Video]]
}
