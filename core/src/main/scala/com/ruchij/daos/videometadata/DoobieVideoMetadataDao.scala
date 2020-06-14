package com.ruchij.daos.videometadata

import com.ruchij.daos.doobie.DoobieCustomMappings._
import com.ruchij.daos.videometadata.models.VideoMetadata
import doobie.ConnectionIO
import doobie.implicits._

object DoobieVideoMetadataDao extends VideoMetadataDao[ConnectionIO] {

  override def insert(videoMetadata: VideoMetadata): ConnectionIO[Int] =
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
