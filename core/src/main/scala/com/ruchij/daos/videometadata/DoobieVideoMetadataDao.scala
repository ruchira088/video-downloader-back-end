package com.ruchij.daos.videometadata

import cats.effect.Bracket
import cats.implicits._
import com.ruchij.daos.doobie.DoobieCustomMappings._
import com.ruchij.daos.resource.FileResourceDao
import com.ruchij.daos.videometadata.models.VideoMetadata
import doobie.ConnectionIO
import doobie.implicits._
import doobie.util.transactor.Transactor

class DoobieVideoMetadataDao[F[_]: Bracket[*[_], Throwable]](
  fileResourceDao: FileResourceDao[F],
  transactor: Transactor.Aux[F, Unit]
) extends VideoMetadataDao[F] {

  override def insert(videoMetadata: VideoMetadata): ConnectionIO[Int] =
    fileResourceDao
      .insert(videoMetadata.thumbnail)
      .product {
        sql"""
          INSERT INTO video_metadata (url, id, video_site, title, duration, size, thumbnail_id)
            VALUES (
              ${videoMetadata.url},
              ${videoMetadata.id},
              ${videoMetadata.videoSite},
              ${videoMetadata.title},
              ${videoMetadata.duration},
              ${videoMetadata.size},
              ${videoMetadata.thumbnail.id}
            )
        """.update.run
      }
      .map { case (resourceResult, metadataResult) => resourceResult + metadataResult }

  override def add(videoMetadata: VideoMetadata): F[Int] =
    insert(videoMetadata).transact(transactor)
}
