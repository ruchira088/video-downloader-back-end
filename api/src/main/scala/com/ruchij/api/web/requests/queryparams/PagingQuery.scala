package com.ruchij.api.web.requests.queryparams

import cats.MonadThrow
import cats.data.Kleisli
import com.ruchij.api.web.requests.queryparams.QueryParameter.QueryParameters
import com.ruchij.api.web.requests.queryparams.SingleValueQueryParameter.{PageNumberQueryParameter, PageSizeQueryParameter}

final case class PagingQuery(pageSize: Int, pageNumber: Int)

object PagingQuery {
  def from[F[_]: MonadThrow]: Kleisli[F, QueryParameters, PagingQuery] =
    for {
      pageSize <- PageSizeQueryParameter.parse[F]
      pageNumber <- PageNumberQueryParameter.parse[F]
    } yield PagingQuery(pageSize, pageNumber)
}
