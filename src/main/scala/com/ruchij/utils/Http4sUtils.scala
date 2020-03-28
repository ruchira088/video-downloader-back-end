package com.ruchij.utils

import cats.{Applicative, MonadError}
import com.ruchij.exceptions.ExternalServiceException
import org.http4s.Response
import org.http4s.headers.`Content-Length`

object Http4sUtils {

  def contentLength[F[_]: MonadError[*[_], Throwable]](response: Response[F]): F[Long] =
    response.headers
      .get(`Content-Length`)
      .fold[F[Long]](
        MonadError[F, Throwable].raiseError(
          ExternalServiceException(s"""Response did not contain the "${`Content-Length`.name}" header""")
        )
      ) { contentLength =>
        Applicative[F].pure(contentLength.length)
      }
}
