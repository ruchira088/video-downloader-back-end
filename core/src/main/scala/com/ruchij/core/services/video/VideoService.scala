package com.ruchij.core.services.video

import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.video.models.{DurationRange, VideoServiceSummary}

trait VideoService[F[_]] {
  def insert(videoMetadataKey: String, fileResourceKey: String): F[Video]

  def fetchById(videoId: String): F[Video]

  def fetchVideoSnapshots(videoId: String): F[Seq[Snapshot]]

  def update(videoId: String, title: Option[String]): F[Video]

  def deleteById(videoId: String, deleteVideoFile: Boolean): F[Video]

  def search(
    term: Option[String],
    durationRange: DurationRange,
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order
  ): F[Seq[Video]]

  val summary: F[VideoServiceSummary]
}
