package com.ruchij.services.video

import cats.data.NonEmptyList
import com.ruchij.daos.snapshot.models.Snapshot
import com.ruchij.daos.video.models.Video
import com.ruchij.services.models.{Order, SortBy}
import org.http4s.Uri

trait VideoService[F[_]] {
  def insert(videoMetadataKey: String, fileResourceKey: String): F[Video]

  def fetchById(videoId: String): F[Video]

  def fetchVideoSnapshots(videoId: String): F[Seq[Snapshot]]

  def update(videoId: String, title: Option[String]): F[Video]

  def deleteById(videoId: String): F[Video]

  def search(
    term: Option[String],
    videoUrls: Option[NonEmptyList[Uri]],
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order
  ): F[Seq[Video]]
}
