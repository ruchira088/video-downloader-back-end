package com.ruchij.api.web.requests.queryparams

import cats.MonadError
import cats.data.{Kleisli, NonEmptyList}
import com.ruchij.api.web.requests.queryparams.MultiValueQueryParameter.{
  SchedulingStatusesQueryParameter,
  VideoUrlsQueryParameter
}
import com.ruchij.api.web.requests.queryparams.QueryParameter.QueryParameters
import com.ruchij.api.web.requests.queryparams.SingleValueQueryParameter._
import com.ruchij.core.daos.scheduling.models.SchedulingStatus
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.video.models.DurationRange
import org.http4s.Uri

case class SearchQuery(
  term: Option[String],
  statuses: Option[NonEmptyList[SchedulingStatus]],
  durationRange: DurationRange,
  urls: Option[NonEmptyList[Uri]],
  pageSize: Int,
  pageNumber: Int,
  sortBy: SortBy,
  order: Order
)

object SearchQuery {
  def fromQueryParameters[F[_]: MonadError[*[_], Throwable]]: Kleisli[F, QueryParameters, SearchQuery] =
    for {
      searchTerm <- SearchTermQueryParameter.parse[F]
      durationRange <- DurationRangeQueryParameter.parse[F]
      schedulingStatuses <- SchedulingStatusesQueryParameter.parse[F]
      urls <- VideoUrlsQueryParameter.parse[F]
      pageSize <- PageSizeQueryParameter.parse[F]
      pageNumber <- PageNumberQueryParameter.parse[F]
      sortBy <- SortByQueryParameter.parse[F]
      order <- OrderQueryParameter.parse[F]
    } yield SearchQuery(searchTerm, schedulingStatuses, durationRange, urls, pageSize, pageNumber, sortBy, order)
}
