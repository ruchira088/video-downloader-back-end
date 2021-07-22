package com.ruchij.core.utils

import cats.data.Kleisli
import cats.{Applicative, MonadError}
import com.ruchij.core.exceptions.ExternalServiceException
import org.http4s.{HeaderKey, Response}

object Http4sUtils {
  val ChunkSize: Long = 5 * 1000 * 1000

  def header[F[_]: MonadError[*[_], Throwable]](
    headerKey: HeaderKey.Extractable
  ): Kleisli[F, Response[F], headerKey.HeaderT] =
    Kleisli { response =>
      response.headers
        .get(headerKey)
        .fold[F[headerKey.HeaderT]](MonadError[F, Throwable].raiseError {
          ExternalServiceException(
            s"""Response did not contain the "${headerKey.name}" header. Headers: ${response.headers.toList
              .map(header => s"${header.name}->${header.value}")
              .mkString(",")}"""
          )
        })(value => Applicative[F].pure(value))
    }
}
