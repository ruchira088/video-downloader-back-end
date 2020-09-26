package com.ruchij.core.services.download.models

import fs2.Stream
import org.http4s.{MediaType, Uri}

case class DownloadResult[F[_]](
  uri: Uri,
  downloadedFileKey: String,
  size: Long,
  mediaType: MediaType,
  data: Stream[F, Long]
)

object DownloadResult {
  def create[F[_]](uri: Uri, downloadedFileKey: String, size: Long, mediaType: MediaType)(
    data: Stream[F, Long]
  ): DownloadResult[F] =
    DownloadResult(uri, downloadedFileKey, size, mediaType, data)
}
