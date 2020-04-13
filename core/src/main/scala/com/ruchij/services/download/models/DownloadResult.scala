package com.ruchij.services.download.models

import fs2.Stream

case class DownloadResult[F[_]](key: String, size: Long, data: Stream[F, Long])

object DownloadResult {
  def create[F[_]](key: String, size: Long)(data: Stream[F, Long]): DownloadResult[F] =
    DownloadResult(key, size, data)
}
