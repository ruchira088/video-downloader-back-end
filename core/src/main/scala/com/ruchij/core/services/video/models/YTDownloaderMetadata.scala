package com.ruchij.core.services.video.models

import com.ruchij.core.services.video.models.YTDownloaderMetadata.Format
import org.http4s.Uri

case class YTDownloaderMetadata(
  title: String,
  extractor: String,
  duration: Double,
  thumbnail: Option[Uri],
  formats: List[Format]
)

object YTDownloaderMetadata {
  case class Format(filesize: Option[Double])
}
