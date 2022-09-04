package com.ruchij.core.services.video.models

import com.ruchij.core.services.video.models.YTDownloaderMetadata.Format
import org.http4s.Uri

final case class YTDownloaderMetadata(
  title: String,
  extractor: String,
  duration: Double,
  thumbnail: Option[Uri],
  formats: List[Format]
)

object YTDownloaderMetadata {
  final case class Format(filesize: Option[Double])
}
