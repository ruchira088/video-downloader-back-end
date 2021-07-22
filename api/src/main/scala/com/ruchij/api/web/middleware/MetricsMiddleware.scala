package com.ruchij.api.web.middleware

import cats.Monad
import cats.implicits._
import cats.data.Kleisli
import cats.effect.Clock
import com.ruchij.core.messaging.Publisher
import com.ruchij.core.messaging.models.HttpMetric
import org.http4s.headers.{`Content-Length`, `Content-Type`}
import org.http4s.{HttpApp, Request, Response}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

object MetricsMiddleware {

  def apply[F[_]: Clock: Monad](metricPublisher: Publisher[F, HttpMetric])(http: HttpApp[F]): HttpApp[F] =
    Kleisli[F, Request[F], Response[F]] {
      request =>
        for {
          startTime <- Clock[F].realTime(TimeUnit.MILLISECONDS)

          response <- http.run(request)

          endTime <- Clock[F].realTime(TimeUnit.MILLISECONDS)

          maybeContentType = response.headers.get(`Content-Type`).map(_.mediaType)
          maybeContentLength = response.headers.get(`Content-Length`).map(_.length)

          _ <-
            metricPublisher.publish {
              HttpMetric(
                request.method,
                request.uri,
                FiniteDuration(endTime - startTime, TimeUnit.MILLISECONDS),
                response.status,
                maybeContentType,
                maybeContentLength
              )
            }
        }
        yield response

    }

}
