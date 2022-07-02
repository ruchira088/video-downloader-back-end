package com.ruchij.api.web.requests.queryparams

import cats.MonadThrow
import cats.data.{Kleisli, NonEmptyList}
import com.ruchij.api.web.requests.queryparams.MultiValueQueryParameter.{SchedulingStatusesQueryParameter, VideoSiteQueryParameter, VideoUrlsQueryParameter}
import com.ruchij.api.web.requests.queryparams.QueryParameter.{QueryParameters, enumQueryParamDecoder}
import com.ruchij.api.web.requests.queryparams.SingleValueQueryParameter._
import com.ruchij.core.daos.scheduling.models.{RangeValue, SchedulingStatus}
import com.ruchij.core.daos.videometadata.models.VideoSite
import com.ruchij.core.services.models.SortBy
import org.http4s.Uri

import scala.concurrent.duration.FiniteDuration

case class SearchQuery(
  term: Option[String],
  statuses: Option[NonEmptyList[SchedulingStatus]],
  durationRange: RangeValue[FiniteDuration],
  sizeRange: RangeValue[Long],
  urls: Option[NonEmptyList[Uri]],
  videoSites: Option[NonEmptyList[VideoSite]],
  pagingQuery: PagingQuery[SortBy]
)

object SearchQuery {
  def fromQueryParameters[F[_]: MonadThrow]: Kleisli[F, QueryParameters, SearchQuery] =
    for {
      searchTerm <- SearchTermQueryParameter.parse[F]
      durationRange <- DurationRangeQueryParameter.parse[F]
      sizeRange <- SizeRangeQueryParameter.parse[F]
      schedulingStatuses <- SchedulingStatusesQueryParameter.parse[F]
      urls <- VideoUrlsQueryParameter.parse[F]
      videoSites <- VideoSiteQueryParameter.parse[F]
      pageQuery <- PagingQuery.from[F, SortBy]
    } yield
      SearchQuery(searchTerm, schedulingStatuses, durationRange, sizeRange, urls, videoSites, pageQuery)
}
