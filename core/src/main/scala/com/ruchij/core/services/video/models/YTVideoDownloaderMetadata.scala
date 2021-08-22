package com.ruchij.core.services.video.models

import com.ruchij.core.services.video.models.YTVideoDownloaderMetadata.Format
import org.http4s.Uri

case class YTVideoDownloaderMetadata(
  title: String,
  extractor: String,
  duration: Int,
  thumbnail: Uri,
  formats: List[Format]
)

object YTVideoDownloaderMetadata {
  case class Format(filesize: Option[Double])
}
