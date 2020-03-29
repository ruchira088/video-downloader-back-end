package com.ruchij.daos.video

import com.ruchij.daos.video.models.Video

trait VideoDao[F[_]] {
  def insert(video: Video): F[Int]
}
