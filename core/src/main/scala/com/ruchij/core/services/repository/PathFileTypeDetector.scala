package com.ruchij.core.services.repository

import cats.effect.Sync
import cats.implicits._
import com.ruchij.core.types.FunctionKTypes.{FunctionK2TypeOps, eitherToF}
import fs2.io.file.Path
import org.apache.tika.Tika
import org.http4s.MediaType

class PathFileTypeDetector[F[_]: Sync](tika: Tika) extends FileTypeDetector[F, Path] {

  override def detect(path: Path): F[MediaType] =
    Sync[F]
      .blocking(tika.detect(path.toNioPath))
      .flatMap { fileType =>
        MediaType.parse(fileType).toType[F, Throwable]
      }

}
