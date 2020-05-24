package com.ruchij.services.repository

import java.nio.file.{Path, Paths}

import cats.effect.{Blocker, ContextShift, Sync}
import cats.implicits._
import cats.{Applicative, ApplicativeError}
import fs2.Stream
import fs2.io.file.{readRange, writeAll, size => fileSize}

class FileRepositoryService[F[_]: Sync: ContextShift](ioBlocker: Blocker) extends RepositoryService[F] {

  override def write(key: String, data: Stream[F, Byte]): Stream[F, Unit] =
    Stream
      .eval(FileRepositoryService.parsePath[F](key))
      .flatMap(path => writeAll(path, ioBlocker).apply(data))

  override def read(key: String, start: Option[Long], end: Option[Long]): F[Option[Stream[F, Byte]]] =
    FileRepositoryService.parsePath[F](key)
      .flatMap { path =>
        Sync[F]
          .delay(path.toFile.exists())
          .flatMap { fileExists =>
            if (fileExists)
              Sync[F].delay(
                Some(readRange(path, ioBlocker, FileRepositoryService.CHUNK_SIZE, start.getOrElse(0), end.getOrElse(Long.MaxValue)))
              )
            else
              Applicative[F].pure(None)
          }
      }

  override def size(key: String): F[Option[Long]] =
    for {
      path <- FileRepositoryService.parsePath[F](key)
      fileExists <- Sync[F].delay(path.toFile.exists())

      bytes <- if (fileExists) fileSize(ioBlocker, path).map[Option[Long]](Some.apply) else Applicative[F].pure[Option[Long]](None)
    }
    yield bytes
}

object FileRepositoryService {
  val CHUNK_SIZE: Int = 4096

  def parsePath[F[_]](path: String)(implicit applicativeError: ApplicativeError[F, Throwable]): F[Path] =
    applicativeError.catchNonFatal(Paths.get(path))
}
