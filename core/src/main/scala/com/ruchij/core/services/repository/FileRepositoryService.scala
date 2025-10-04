package com.ruchij.core.services.repository

import cats.effect._
import cats.implicits._
import cats.{Applicative, ApplicativeError}
import com.ruchij.core.logging.Logger
import com.ruchij.core.utils.Timers
import fs2.Stream
import fs2.io.file.{Files, Flags, Path, WalkOptions}
import org.http4s.MediaType

import java.nio.file.Paths
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class FileRepositoryService[F[_]: Async: Files](fileTypeDetector: FileTypeDetector[F, Path])
    extends RepositoryService[F] {

  override type BackedType = Path

  private val logger = Logger[FileRepositoryService[F]]

  override def write(key: String, data: Stream[F, Byte]): Stream[F, Nothing] =
    for {
      path <- Stream.eval(backedType(key))
      exists <- Stream.eval(Files[F].exists(path))

      result <- data.through {
        Files[F].writeAll(path, if (exists) Flags.Append else Flags.Write)
      }
    } yield result

  override def read(key: String, start: Option[Long], end: Option[Long]): F[Option[Stream[F, Byte]]] =
    backedType(key)
      .flatMap { path =>
        Files[F]
          .exists(path)
          .flatMap { exists =>
            if (exists)
              Sync[F].delay(
                Some(
                  Files[F]
                    .readRange(path, FileRepositoryService.CHUNK_SIZE, start.getOrElse(0), end.getOrElse(Long.MaxValue))
                )
              )
            else
              Applicative[F].pure(None)
          }
      }

  override def size(key: String): F[Option[Long]] =
    for {
      path <- backedType(key)
      fileExists <- Files[F].exists(path)

      bytes <- if (fileExists) Files[F].size(path).map[Option[Long]](Some.apply)
      else Applicative[F].pure[Option[Long]](None)
    } yield bytes

  override def exists(key: Key): F[Boolean] =
    for {
      path <- backedType(key)
      fileExists <- Files[F].exists(path)
    } yield fileExists

  override def list(key: Key): Stream[F, Key] =
    Stream.eval(Ref.of[F, Boolean](false)).flatMap { ref =>
      Stream
        .eval(logger.info[F](s"Listing files for $key"))
        .interruptWhen {
          Timers
            .createResettableTimer(100 seconds, ref)
            .recoverWith {
              case timeoutException: TimeoutException =>
                logger
                  .error[F](s"Unable to list files for $key", timeoutException)
                  .as(Left(timeoutException))
            }
        }
        .productR {
          Stream
            .eval(backedType(key))
            .flatMap(path => Files[F].walk(path, WalkOptions.Default.withMaxDepth(Int.MaxValue).withFollowLinks(true)))
            .evalTap { _ =>
              ref.set(true)
            }
            .map(_.toString)
        }
    }

  override def backedType(key: Key): F[Path] = FileRepositoryService.parsePath[F](key)

  override def delete(key: Key): F[Boolean] =
    for {
      path <- backedType(key)
      result <- Files[F].deleteIfExists(path)
    } yield result

  override def fileType(key: Key): F[Option[MediaType]] =
    for {
      path <- backedType(key)
      fileExists <- Files[F].exists(path)

      result <- if (fileExists) fileTypeDetector.detect(path).map(Some.apply) else Applicative[F].pure(None)
    } yield result
}

object FileRepositoryService {
  type FileRepository[F[_], A] = RepositoryService[F] { type BackedType = A }

  private val CHUNK_SIZE: Int = 4096

  private def parsePath[F[_]](path: String)(implicit applicativeError: ApplicativeError[F, Throwable]): F[Path] =
    applicativeError.catchNonFatal(Path.fromNioPath(Paths.get(path)))
}
