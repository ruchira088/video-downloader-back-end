package com.ruchij.api.web.middleware

import cats.data.Kleisli
import cats.effect.Sync
import cats.implicits._
import com.ruchij.core.monitoring.Sentry
import io.sentry.{Sentry => JavaSentry}
import org.http4s.{ContextRequest, Response, Status}

object SentryMiddleware {

  def apply[F[_]: Sync, A](
    httpApp: Kleisli[F, ContextRequest[F, A], Response[F]]
  ): Kleisli[F, ContextRequest[F, A], Response[F]] =
    Kleisli[F, ContextRequest[F, A], Response[F]] { contextRequest =>
      Sync[F].handleErrorWith(httpApp.run(contextRequest)) { throwable =>
        captureWithContext[F, A](throwable, contextRequest) *>
          Sync[F].raiseError[Response[F]](throwable)
      }
    }

  private def captureWithContext[F[_]: Sync, A](
    throwable: Throwable,
    contextRequest: ContextRequest[F, A]
  ): F[Unit] =
    Sync[F].delay {
      JavaSentry.withScope { scope =>
        val request = contextRequest.req
        scope.setTag("http.method", request.method.name)
        scope.setTag("http.url", request.uri.renderString)
        request.uri.path.segments.headOption.foreach { segment =>
          scope.setTag("http.route", s"/${segment.encoded}")
        }
        request.remote.foreach { remote =>
          scope.setTag("client.address", remote.host.toString)
        }

        JavaSentry.captureException(throwable)
      }
    }

  def captureResponseErrors[F[_]: Sync, A](
    httpApp: Kleisli[F, ContextRequest[F, A], Response[F]]
  ): Kleisli[F, ContextRequest[F, A], Response[F]] =
    Kleisli[F, ContextRequest[F, A], Response[F]] { contextRequest =>
      httpApp.run(contextRequest).flatTap { response =>
        if (response.status >= Status.InternalServerError) {
          Sentry.captureMessage[F](
            s"HTTP ${response.status.code}: ${contextRequest.req.method.name} ${contextRequest.req.uri.renderString}"
          )
        } else {
          Sync[F].unit
        }
      }
    }
}
