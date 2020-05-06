package com.ruchij.daos.videometadata

import cats.implicits._
import com.ruchij.daos.doobie.DoobieCustomMappings._
import com.ruchij.daos.resource.FileResourceDao
import com.ruchij.daos.videometadata.models.VideoMetadata
import doobie.ConnectionIO
import doobie.implicits._

class DoobieVideoMetadataDao[F[_]](fileResourceDao: FileResourceDao[F]) extends VideoMetadataDao[F] {

  override def insert(videoMetadata: VideoMetadata): ConnectionIO[Int] =
    fileResourceDao.insert(videoMetadata.thumbnail)
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
      .map { case (resourceResult, metadataResult) =>  resourceResult + metadataResult }
}
