package com.ruchij.api.web.middleware

import cats.arrow.FunctionK
import cats.data.Kleisli
import cats.effect.Sync
import com.ruchij.api.web.responses.ErrorResponse
import com.ruchij.core.exceptions.{AggregatedException, ResourceNotFoundException}
import com.ruchij.core.types.FunctionKTypes
import org.http4s.dsl.impl.EntityResponseGenerator
import org.http4s.{HttpApp, Request, Response, Status}

object ExceptionHandler {
  def apply[F[_]: Sync](httpApp: HttpApp[F]): HttpApp[F] =
    Kleisli[F, Request[F], Response[F]] {
      request =>
        Sync[F].handleErrorWith(httpApp.run(request)) { throwable =>
          entityResponseGenerator(throwable)(errorResponseBody(throwable))
        }
    }

  def entityResponseGenerator[F[_]](throwable: Throwable): EntityResponseGenerator[F, F] =
    new EntityResponseGenerator[F, F] {
      override def status: Status =
        throwable match {
          case _: ResourceNotFoundException => Status.NotFound

          case _ => Status.InternalServerError
        }

      override def liftG: FunctionK[F, F] = FunctionKTypes.identityFunctionK[F]
    }

  def errorResponseBody(throwable: Throwable): ErrorResponse =
    throwable match {
      case AggregatedException(exceptions) => ErrorResponse(exceptions.toList)
      case _ => ErrorResponse(List(throwable))
    }
}
