package com.ruchij.services.video

import com.ruchij.daos.resource.models.FileResource
import com.ruchij.daos.snapshot.models.Snapshot
import com.ruchij.daos.video.models.Video
import com.ruchij.services.models.{Order, SortBy}

trait VideoService[F[_]] {
  def insert(videoMetadataKey: String, fileResource: FileResource): F[Video]

  def fetchById(videoId: String): F[Video]

  def fetchVideoSnapshots(videoId: String): F[Seq[Snapshot]]

  def update(videoId: String, title: Option[String]): F[Video]

  def search(term: Option[String], pageNumber: Int, pageSize: Int, sortBy: SortBy, order: Order): F[Seq[Video]]
}
