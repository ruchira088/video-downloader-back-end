package com.ruchij.core.services.repository


import java.nio.file.{FileVisitOption, Path, Paths, StandardOpenOption}

import cats.effect.{Blocker, ContextShift, Sync}
import cats.implicits._
import cats.{Applicative, ApplicativeError}
import fs2.Stream
import fs2.io.file.{deleteIfExists, exists, readRange, walk, writeAll, size => fileSize}

class FileRepositoryService[F[_]: Sync: ContextShift](ioBlocker: Blocker) extends RepositoryService[F] {

  override type BackedType = Path

  override def write(key: String, data: Stream[F, Byte]): Stream[F, Unit] =
    for {
      path <- Stream.eval(backedType(key))
      exists <- Stream.eval(exists(ioBlocker, path))

      result <- data.through {
        writeAll(path, ioBlocker, if (exists) List(StandardOpenOption.APPEND) else List(StandardOpenOption.CREATE))
      }
    } yield result

  override def read(key: String, start: Option[Long], end: Option[Long]): F[Option[Stream[F, Byte]]] =
    backedType(key)
      .flatMap { path =>
        exists(ioBlocker, path)
          .flatMap { exists =>
            if (exists)
              Sync[F].delay(
                Some(
                  readRange(
                    path,
                    ioBlocker,
                    FileRepositoryService.CHUNK_SIZE,
                    start.getOrElse(0),
                    end.getOrElse(Long.MaxValue)
                  )
                )
              )
            else
              Applicative[F].pure(None)
          }
      }

  override def size(key: String): F[Option[Long]] =
    for {
      path <- backedType(key)
      fileExists <- exists(ioBlocker, path)

      bytes <- if (fileExists) fileSize(ioBlocker, path).map[Option[Long]](Some.apply)
      else Applicative[F].pure[Option[Long]](None)
    } yield bytes

  override def list(key: Key): Stream[F, Key] =
    Stream
      .eval(backedType(key))
      .flatMap(path => walk(ioBlocker, path, Seq(FileVisitOption.FOLLOW_LINKS)))
      .drop(1) // drop the parent key
      .map(_.toString)

  override def backedType(key: Key): F[Path] = FileRepositoryService.parsePath[F](key)

  override def delete(key: Key): F[Boolean] =
    for {
      path <- backedType(key)
      result <- deleteIfExists(ioBlocker, path)
    } yield result
}

object FileRepositoryService {
  type FileRepository[F[_], A] = RepositoryService[F] { type BackedType = A }

  val CHUNK_SIZE: Int = 4096

  def parsePath[F[_]](path: String)(implicit applicativeError: ApplicativeError[F, Throwable]): F[Path] =
    applicativeError.catchNonFatal(Paths.get(path))
}
