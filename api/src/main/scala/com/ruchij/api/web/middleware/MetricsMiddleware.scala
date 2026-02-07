package com.ruchij.api.web.middleware

import cats.Monad
import cats.implicits._
import cats.data.Kleisli
import com.ruchij.core.messaging.Publisher
import com.ruchij.core.messaging.models.HttpMetric
import com.ruchij.core.types.Clock
import org.http4s.headers.{`Content-Length`, `Content-Type`}
import org.http4s.{HttpApp, Request, Response}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

object MetricsMiddleware {

  def apply[F[_]: Clock: Monad](metricPublisher: Publisher[F, HttpMetric])(http: HttpApp[F]): HttpApp[F] =
    Kleisli[F, Request[F], Response[F]] {
      request =>
        for {
          startTime <- Clock[F].timestamp

          response <- http.run(request)

          endTime <- Clock[F].timestamp

          maybeContentType = response.headers.get[`Content-Type`].map(_.mediaType)
          maybeContentLength = response.headers.get[`Content-Length`].map(_.length)

          _ <-
            metricPublisher.publishOne {
              HttpMetric(
                request.method,
                request.uri,
                FiniteDuration(endTime.toEpochMilli - startTime.toEpochMilli, TimeUnit.MILLISECONDS),
                response.status,
                maybeContentType,
                maybeContentLength
              )
            }
        }
        yield response

    }

}
