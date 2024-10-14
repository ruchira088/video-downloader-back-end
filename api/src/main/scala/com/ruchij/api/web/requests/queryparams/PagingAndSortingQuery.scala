package com.ruchij.api.web.requests.queryparams

import cats.MonadThrow
import cats.data.Kleisli
import com.ruchij.api.web.requests.queryparams.QueryParameter.{QueryParameters, optionQueryParamDecoder}
import com.ruchij.api.web.requests.queryparams.SingleValueQueryParameter.OrderQueryParameter
import com.ruchij.core.services.models.Order
import org.http4s.QueryParamDecoder

final case class PagingAndSortingQuery[A](pageSize: Int, pageNumber: Int, order: Order, maybeSortBy: Option[A])

object PagingAndSortingQuery {
  private def sortByQueryParameter[A: QueryParamDecoder]: SingleValueQueryParameter[Option[A]] =
    new SingleValueQueryParameter[Option[A]]("sort-by", None) {}

  def from[F[_]: MonadThrow, A: QueryParamDecoder]: Kleisli[F, QueryParameters, PagingAndSortingQuery[A]] =
    for {
      pagingQuery <- PagingQuery.from[F]
      maybeSortBy <- sortByQueryParameter[A].parse[F]
      order <- OrderQueryParameter.parse[F]
    }
    yield PagingAndSortingQuery(pagingQuery.pageSize, pagingQuery.pageNumber, order, maybeSortBy)
}
