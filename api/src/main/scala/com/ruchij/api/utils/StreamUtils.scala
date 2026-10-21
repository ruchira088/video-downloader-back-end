package com.ruchij.api.utils

import cats.{Applicative, ApplicativeError}
import fs2.{Chunk, Pipe, Stream}

object StreamUtils {

  /** Fails the stream with the given error as soon as more than maxSize bytes have passed through it */
  def limitSize[F[_]: ApplicativeError[*[_], Throwable]](maxSize: Long)(onExceeded: => Throwable): Pipe[F, Byte, Byte] =
    _.chunks
      .evalMapAccumulate(0L) { (total, chunk) =>
        val updatedTotal = total + chunk.size

        if (updatedTotal > maxSize) ApplicativeError[F, Throwable].raiseError[(Long, Chunk[Byte])](onExceeded)
        else Applicative[F].pure(updatedTotal -> chunk)
      }
      .flatMap { case (_, chunk) => Stream.chunk(chunk) }
}
