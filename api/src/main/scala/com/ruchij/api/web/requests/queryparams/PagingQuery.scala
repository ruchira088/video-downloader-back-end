package com.ruchij.api.web.requests.queryparams

import cats.MonadThrow
import cats.data.Kleisli
import com.ruchij.api.web.requests.queryparams.QueryParameter.{QueryParameters, optionQueryParamDecoder}
import com.ruchij.api.web.requests.queryparams.SingleValueQueryParameter.{OrderQueryParameter, PageNumberQueryParameter, PageSizeQueryParameter}
import com.ruchij.core.services.models.Order
import org.http4s.QueryParamDecoder

case class PagingQuery[A](pageSize: Int, pageNumber: Int, order: Order, maybeSortBy: Option[A])

object PagingQuery {
  def sortByQueryParameter[A: QueryParamDecoder]: SingleValueQueryParameter[Option[A]] =
    new SingleValueQueryParameter[Option[A]]("sort-by", None) {}

  def from[F[_]: MonadThrow, A: QueryParamDecoder]: Kleisli[F, QueryParameters, PagingQuery[A]] =
    for {
      pageSize <- PageSizeQueryParameter.parse[F]
      pageNumber <- PageNumberQueryParameter.parse[F]
      maybeSortBy <- sortByQueryParameter[A].parse[F]
      order <- OrderQueryParameter.parse[F]
    }
    yield PagingQuery(pageSize, pageNumber, order, maybeSortBy)
}
