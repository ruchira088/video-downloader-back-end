package com.ruchij.services.download.models

import java.nio.file.Path

import fs2.Stream

case class DownloadResult[F[_]](path: Path, size: Long, data: Stream[F, Long])

object DownloadResult {
  def create[F[_]](path: Path, size: Long)(data: Stream[F, Long]): DownloadResult[F] =
    DownloadResult(path, size, data)
}
