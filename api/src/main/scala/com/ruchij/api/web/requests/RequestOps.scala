package com.ruchij.api.web.requests

import cats.{MonadThrow, Show}
import cats.implicits._
import com.ruchij.core.exceptions.ValidationException
import io.circe.DecodingFailure
import org.http4s.{ContextRequest, DecodeFailure, EntityDecoder, Request}

object RequestOps {
  def to[F[_]: MonadThrow, A](
    request: Request[F]
  )(implicit entityDecoder: EntityDecoder[F, A], validator: Validator[F, A]): F[A] =
    for {
      entity <- request.as[A].adaptError { case decodeFailure: DecodeFailure => invalidBody(decodeFailure) }
      validatedEntity <- validator.validate[A](entity)
    } yield validatedEntity

  /**
    * A failure to decode the request body describes the client's own request, so it is returned to the client as a
    * ValidationException, whereas the message of any other http4s MessageFailure is withheld by the ExceptionHandler
    */
  private def invalidBody(decodeFailure: DecodeFailure): ValidationException =
    decodeFailure.cause match {
      case Some(decodingFailure: DecodingFailure) =>
        ValidationException(s"Invalid request body: ${Show[DecodingFailure].show(decodingFailure)}")

      case _ => ValidationException(decodeFailure.message)
    }

  implicit class RequestOpsSyntax[F[_]: MonadThrow](request: Request[F]) {
    def to[A](implicit entityDecoder: EntityDecoder[F, A], validator: Validator[F, A]): F[A] =
      RequestOps.to[F, A](request)
  }

  implicit class ContextRequestOpsSyntax[F[_]: MonadThrow, T](contextRequest: ContextRequest[F, T]) {
    def to[A](implicit entityDecoder: EntityDecoder[F, A], validator: Validator[F, A]): F[A] =
      RequestOps.to[F, A](contextRequest.req)
  }
}
