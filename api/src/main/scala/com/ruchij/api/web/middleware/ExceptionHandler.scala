package com.ruchij.api.web.middleware

import cats.Show
import cats.arrow.FunctionK
import cats.data.{Kleisli, NonEmptyList}
import cats.effect.Sync
import cats.implicits._
import com.ruchij.api.exceptions.{AuthenticationException, ResourceConflictException}
import com.ruchij.api.web.responses.ErrorResponse
import com.ruchij.core.circe.Encoders.throwableEncoder
import com.ruchij.core.exceptions.{AggregatedException, ExternalServiceException, JSoupException, ResourceNotFoundException, UnsupportedVideoUrlException, ValidationException}
import com.ruchij.core.logging.Logger
import io.circe.DecodingFailure
import io.circe.generic.auto.exportEncoder
import org.http4s.dsl.impl.EntityResponseGenerator
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.{HttpApp, MessageFailure, Request, Response, Status}

object ExceptionHandler {
  private val logger = Logger[ExceptionHandler.type]

  def apply[F[_]: Sync](httpApp: HttpApp[F]): HttpApp[F] =
    Kleisli[F, Request[F], Response[F]] { request =>
      Sync[F].handleErrorWith(httpApp.run(request)) { throwable =>
        entityResponseGenerator[F](throwable)(throwableResponseBody(throwable))
          .map(errorResponseMapper(throwable))
          .flatMap(logErrors[F](throwable))
      }
    }

  def logErrors[F[_]: Sync](throwable: Throwable)(response: Response[F]): F[Response[F]] =
    if (response.status >= Status.InternalServerError)
      logger.error[F](s"${response.status} status code returned", throwable).as(response)
    else logger.warn[F](throwable.getMessage).as(response)

  val throwableStatusMapper: Throwable => Status = {
    case _: ResourceNotFoundException => Status.NotFound

    case _: AuthenticationException => Status.Unauthorized

    case _: DecodingFailure | _: IllegalArgumentException | _: MessageFailure | _: ValidationException | _: UnsupportedVideoUrlException =>
      Status.BadRequest

    case _: JSoupException | _: ExternalServiceException => Status.BadGateway

    case _: ResourceConflictException => Status.Conflict

    case AggregatedException(NonEmptyList(exception, _)) => throwableStatusMapper(exception)

    case _ => Status.InternalServerError
  }

  val throwableResponseBody: Throwable => ErrorResponse = {
    case AggregatedException(exceptions) =>
      ErrorResponse {
        exceptions.map(throwableResponseBody).flatMap(_.errorMessages)
      }

    case decodingFailure: DecodingFailure =>
      ErrorResponse {
        NonEmptyList.one {
          Show[DecodingFailure].show(decodingFailure)
        }
      }

    case throwable => Option(throwable.getCause).fold(ErrorResponse(NonEmptyList.one(throwable.getMessage)))(throwableResponseBody)
  }

  def errorResponseMapper[F[_]](throwable: Throwable)(response: Response[F]): Response[F] =
    throwable match {
      case _: AuthenticationException => response.removeCookie(Authenticator.CookieName)

      case _ => response
    }

  def entityResponseGenerator[F[_]](throwable: Throwable): EntityResponseGenerator[F, F] =
    new EntityResponseGenerator[F, F] {
      override def status: Status = throwableStatusMapper(throwable)

      override def liftG: FunctionK[F, F] = FunctionK.id[F]
    }
}
