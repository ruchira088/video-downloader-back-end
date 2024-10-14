package com.ruchij.core.utils

import cats.data.Kleisli
import cats.{Applicative, MonadError, MonadThrow}
import com.ruchij.core.exceptions.ExternalServiceException
import org.http4s.Header.Select
import org.http4s.{Header, Response}

object Http4sUtils {
  def header[F[_]: MonadThrow, A](implicit select: Select[A], header: Header[A, _]
  ): Kleisli[F, Response[F], select.F[A]] =
    Kleisli { response =>
      response.headers.get[A]
        .fold[F[select.F[A]]](MonadError[F, Throwable].raiseError {
          ExternalServiceException(
            s"""Response did not contain the "${header.name}" header. Headers: ${response.headers.headers
              .map(header => s"${header.name}->${header.value}")
              .mkString(",")}"""
          )
        })(value => Applicative[F].pure(value))
    }
}
