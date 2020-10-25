package com.ruchij.api.web.middleware

import cats.arrow.FunctionK
import cats.data.{Kleisli, NonEmptyList}
import cats.effect.Sync
import cats.implicits._
import com.ruchij.api.exceptions.AuthenticationException
import com.ruchij.api.web.responses.ErrorResponse
import com.ruchij.core.exceptions.{AggregatedException, ExternalServiceException, JSoupException, ResourceConflictException, ResourceNotFoundException, ValidationException}
import com.ruchij.core.types.FunctionKTypes
import io.circe.DecodingFailure
import org.http4s.dsl.impl.EntityResponseGenerator
import org.http4s.{HttpApp, MessageFailure, Request, Response, Status}

object ExceptionHandler {
  def apply[F[_]: Sync](httpApp: HttpApp[F]): HttpApp[F] =
    Kleisli[F, Request[F], Response[F]] { request =>
      Sync[F].handleErrorWith(httpApp.run(request)) { throwable =>
        entityResponseGenerator[F](throwable)(throwableResponseBody(throwable))
          .map(errorResponseMapper(throwable))
      }
    }

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

      override def liftG: FunctionK[F, F] = FunctionKTypes.identityFunctionK[F]
    }
}
