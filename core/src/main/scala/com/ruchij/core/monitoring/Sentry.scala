package com.ruchij.core.monitoring

import cats.effect.{Resource, Sync}
import cats.implicits._
import com.ruchij.core.config.SentryConfiguration
import com.ruchij.core.logging.Logger
import io.sentry.{SentryOptions, Sentry => JavaSentry}

object Sentry {
  private val logger = Logger[Sentry.type]

  def init[F[_]: Sync](sentryConfiguration: SentryConfiguration): Resource[F, Unit] =
    sentryConfiguration.dsn
      .filter(_.trim.nonEmpty)
      .fold(Resource.eval(logger.info[F]("Sentry is disabled (no DSN configured)"))) { dsn =>
        Resource.make(
          Sync[F]
            .delay {
              JavaSentry.init { sentryOptions: SentryOptions =>
                sentryOptions.setDsn(dsn)
                sentryOptions.setEnvironment(sentryConfiguration.environment)
                sentryOptions.setTracesSampleRate(sentryConfiguration.tracesSampleRate)
                sentryOptions.setSendDefaultPii(true)
              }
            }
            .productL {
              logger.info[F](s"Sentry initialized for environment: ${sentryConfiguration.environment}")
            }
        )(_ => Sync[F].delay(JavaSentry.close()))
      }

  def captureException[F[_]: Sync](throwable: Throwable): F[Unit] =
    Sync[F].delay(JavaSentry.captureException(throwable)).void

  def captureMessage[F[_]: Sync](message: String): F[Unit] =
    Sync[F].delay(JavaSentry.captureMessage(message)).void

  def setTag[F[_]: Sync](key: String, value: String): F[Unit] =
    Sync[F].delay {
      JavaSentry.configureScope { scope =>
        scope.setTag(key, value)
      }
    }

  def setExtra[F[_]: Sync](key: String, value: String): F[Unit] =
    Sync[F].delay {
      JavaSentry.configureScope { scope =>
        scope.setExtra(key, value)
      }
    }
}
