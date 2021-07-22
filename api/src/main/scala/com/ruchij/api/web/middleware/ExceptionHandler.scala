package com.ruchij.api.web.middleware

import cats.Applicative
import cats.arrow.FunctionK
import cats.data.{Kleisli, NonEmptyList}
import cats.effect.Sync
import cats.implicits._
import com.ruchij.api.exceptions.AuthenticationException
import com.ruchij.api.web.responses.ErrorResponse
import com.ruchij.core.circe.Encoders.throwableEncoder
import com.ruchij.core.exceptions.{AggregatedException, ExternalServiceException, JSoupException, ResourceConflictException, ResourceNotFoundException, ValidationException}
import com.ruchij.core.logging.Logger
import io.circe.DecodingFailure
import io.circe.generic.auto.exportEncoder
import org.http4s.dsl.impl.EntityResponseGenerator
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.{HttpApp, MessageFailure, Request, Response, Status}

object ExceptionHandler {
  def apply[F[_]: Sync](httpApp: HttpApp[F]): HttpApp[F] = {
    val logger = Logger[F, ExceptionHandler.type]

    Kleisli[F, Request[F], Response[F]] { request =>
      Sync[F].handleErrorWith(httpApp.run(request)) { throwable =>
        entityResponseGenerator[F](throwable)(throwableResponseBody(throwable))
          .map(errorResponseMapper(throwable))
          .flatMap(logErrors[F](logger, throwable))
      }
    }
  }

  def logErrors[F[_]: Applicative](logger: Logger[F], throwable: Throwable)(response: Response[F]): F[Response[F]] =
    if (response.status >= Status.InternalServerError)
      logger.errorF(s"${response.status} status code returned", throwable).as(response)
    else logger.warnF(throwable.getMessage).as(response)

  val throwableStatusMapper: Throwable => Status = {
    case _: ResourceNotFoundException => Status.NotFound

    case _: AuthenticationException => Status.Unauthorized

    case _: DecodingFailure | _: IllegalArgumentException | _: MessageFailure | _: ValidationException =>
      Status.BadRequest

    case _: JSoupException | _: ExternalServiceException => Status.BadGateway

    case _: ResourceConflictException => Status.Conflict

    case AggregatedException(NonEmptyList(exception, _)) => throwableStatusMapper(exception)

    case _ => Status.InternalServerError
  }

  val throwableResponseBody: Throwable => ErrorResponse = {
    case AggregatedException(exceptions) => ErrorResponse(exceptions.toList)
    case throwable => ErrorResponse(List(throwable))
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
