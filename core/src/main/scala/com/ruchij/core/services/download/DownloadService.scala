package com.ruchij.core.services.download

import cats.effect.Resource
import com.ruchij.core.services.download.models.DownloadResult
import org.http4s.Uri

trait DownloadService[F[_]] {
  def download(url: Uri, fileKey: String): Resource[F, DownloadResult[F]]
}
