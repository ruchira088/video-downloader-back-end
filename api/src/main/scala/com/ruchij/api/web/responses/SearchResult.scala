package com.ruchij.api.web.responses

import cats.data.NonEmptyList
import com.ruchij.core.daos.scheduling.models.SchedulingStatus
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.video.models.DurationRange
import org.http4s.Uri

case class SearchResult[A](
  results: Seq[A],
  pageNumber: Int,
  pageSize: Int,
  searchTerm: Option[String],
  videoUrls: Option[NonEmptyList[Uri]],
  statuses: Option[NonEmptyList[SchedulingStatus]],
  durationRange: DurationRange,
  sortBy: SortBy,
  order: Order
)
