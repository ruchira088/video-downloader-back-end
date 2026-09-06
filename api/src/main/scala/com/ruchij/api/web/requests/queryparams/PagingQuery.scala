package com.ruchij.api.web.requests.queryparams

import cats.MonadThrow
import cats.data.Kleisli
import com.ruchij.core.exceptions.ValidationException
import com.ruchij.api.web.requests.queryparams.QueryParameter.QueryParameters
import com.ruchij.api.web.requests.queryparams.SingleValueQueryParameter.{PageNumberQueryParameter, PageSizeQueryParameter}

final case class PagingQuery(pageSize: Int, pageNumber: Int)

object PagingQuery {
  val MaxPageSize: Int = 1000

  def from[F[_]: MonadThrow]: Kleisli[F, QueryParameters, PagingQuery] =
    for {
      pageSize <- PageSizeQueryParameter.parse[F]
      pageNumber <- PageNumberQueryParameter.parse[F]
      pagingQuery <- Kleisli.liftF(validate[F](PagingQuery(pageSize, pageNumber)))
    } yield pagingQuery

  private def validate[F[_]: MonadThrow](pagingQuery: PagingQuery): F[PagingQuery] =
    if (pagingQuery.pageSize < 1 || pagingQuery.pageSize > MaxPageSize)
      MonadThrow[F].raiseError(ValidationException(s"page-size must be between 1 and $MaxPageSize"))
    else if (pagingQuery.pageNumber < 0)
      MonadThrow[F].raiseError(ValidationException("page-number must not be negative"))
    else MonadThrow[F].pure(pagingQuery)
}
