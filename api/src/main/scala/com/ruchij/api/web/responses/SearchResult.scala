package com.ruchij.api.web.responses

import cats.data.NonEmptyList
import com.ruchij.core.daos.scheduling.models.{RangeValue, SchedulingStatus}
import com.ruchij.core.services.models.{Order, SortBy}
import org.http4s.Uri

import scala.concurrent.duration.FiniteDuration

case class SearchResult[A](
  results: Seq[A],
  pageNumber: Int,
  pageSize: Int,
  searchTerm: Option[String],
  videoUrls: Option[NonEmptyList[Uri]],
  statuses: Option[NonEmptyList[SchedulingStatus]],
  durationRange: RangeValue[FiniteDuration],
  sizeRange: RangeValue[Long],
  sortBy: Option[SortBy],
  order: Order
)
