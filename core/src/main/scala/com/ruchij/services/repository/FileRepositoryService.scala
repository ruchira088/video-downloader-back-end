package com.ruchij.services.repository

import java.nio.file.Paths

import cats.Applicative
import cats.effect.{Blocker, ContextShift, Sync}
import cats.implicits._
import fs2.Stream
import fs2.io.file.{readRange, writeAll}

class FileRepositoryService[F[_]: Sync: ContextShift](ioBlocker: Blocker) extends RepositoryService[F] {

  override def write(key: String, data: Stream[F, Byte]): Stream[F, Unit] =
    Stream
      .eval(Sync[F].delay(Paths.get(key)))
      .flatMap(path => writeAll(path, ioBlocker).apply(data))

  override def read(key: String, start: Long, end: Long): F[Option[Stream[F, Byte]]] =
    Sync[F].delay(Paths.get(key))
      .flatMap { path =>
        Sync[F].delay(path.toFile.exists())
          .flatMap { fileExists =>
            if (fileExists)
              Sync[F].delay(Some(readRange(path, ioBlocker, __block_size, start, end)))
            else
              Applicative[F].pure(None)
          }
      }
}
