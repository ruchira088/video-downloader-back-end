package com.ruchij.services.repository

import java.util.concurrent.ConcurrentHashMap

import cats.Applicative
import cats.effect.Sync
import cats.implicits._
import fs2.Stream

import scala.util.Try

class InMemoryRepositoryService[F[_]: Sync](concurrentHashMap: ConcurrentHashMap[String, List[Byte]])
    extends RepositoryService[F] {

  override def write(key: String, data: Stream[F, Byte]): Stream[F, Unit] =
    Stream.eval[F, Unit] {
      data.compile.toList
        .flatMap {
          bytes => Sync[F].delay(concurrentHashMap.put(key, bytes))
        }
        .as((): Unit)
    }

  override def read(key: String, start: Option[Long], end: Option[Long]): F[Option[Stream[F, Byte]]] =
    Sync[F].delay(Option(concurrentHashMap.get(key)))
      .flatMap {
        _.fold[F[Option[Stream[F, Byte]]]](Applicative[F].pure(None)) { bytes =>
          val startIndex = start.flatMap(long => Try(long.toInt).toOption).getOrElse(0)
          val length = end.flatMap(long => Try(long.toInt).toOption).getOrElse(bytes.length) - startIndex

          Applicative[F].pure {
            Some(Stream.emits[F, Byte](bytes.slice(startIndex, startIndex + length)))
          }
        }
      }
}
