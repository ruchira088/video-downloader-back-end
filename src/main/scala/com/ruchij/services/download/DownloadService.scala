package com.ruchij.services.download

import java.nio.file.Path

import cats.effect.Resource
import com.ruchij.services.download.models.DownloadResult
import org.http4s.Uri

trait DownloadService[F[_]] {
  def download(url: Uri, folder: Path): Resource[F, DownloadResult[F]]
}
