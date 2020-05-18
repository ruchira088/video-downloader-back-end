package com.ruchij.utils

import cats.data.Kleisli
import cats.{Applicative, MonadError}
import com.ruchij.exceptions.ExternalServiceException
import org.http4s.{HeaderKey, Response}

object Http4sUtils {

  def header[F[_]: MonadError[*[_], Throwable]](headerKey: HeaderKey.Extractable): Kleisli[F, Response[F], headerKey.HeaderT] =
    Kleisli {
      _.headers
        .get(headerKey)
        .fold[F[headerKey.HeaderT]](MonadError[F, Throwable].raiseError {
          ExternalServiceException(s"""Response did not contain the "${headerKey.name}" header""")
        })(value => Applicative[F].pure(value))
    }

}
