package com.ruchij.web.requests.queryparams

import cats.MonadError
import cats.data.{Kleisli, NonEmptyList}
import com.ruchij.services.models.{Order, SortBy}
import com.ruchij.web.requests.queryparams.QueryParameter.QueryParameters
import com.ruchij.web.requests.queryparams.MultiValueQueryParameter.VideoUrlsQueryParameter
import com.ruchij.web.requests.queryparams.SingleValueQueryParameter._
import org.http4s.Uri

case class SearchQuery(term: Option[String], urls: Option[NonEmptyList[Uri]], pageSize: Int, pageNumber: Int, sortBy: SortBy, order: Order)

object SearchQuery {
  def fromQueryParameters[F[_]: MonadError[*[_], Throwable]]: Kleisli[F, QueryParameters, SearchQuery] =
    for {
      searchTerm <- SearchTermQueryParameter.parse[F]
      urls <- VideoUrlsQueryParameter.parse[F]
      pageSize <- PageSizeQueryParameter.parse[F]
      pageNumber <- PageNumberQueryParameter.parse[F]
      sortBy <- SortByQueryParameter.parse[F]
      order <- OrderQueryParameter.parse[F]
    }
    yield SearchQuery(searchTerm, urls, pageSize, pageNumber, sortBy, order)
}