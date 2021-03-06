package com.ruchij.core.services.repository

import java.nio.file.Path

import cats.effect.{Blocker, ContextShift, Sync}
import cats.implicits._
import com.ruchij.core.types.FunctionKTypes
import org.apache.tika.Tika
import org.http4s.MediaType

class PathFileTypeDetector[F[_]: Sync: ContextShift](tika: Tika, ioBlocker: Blocker) extends FileTypeDetector[F, Path] {

  override def detect(path: Path): F[MediaType] =
    ioBlocker
      .delay[F, String](tika.detect(path))
      .flatMap { fileType =>
        FunctionKTypes.eitherToF[Throwable, F].apply(MediaType.parse(fileType))
      }

}
