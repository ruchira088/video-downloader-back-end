package com.ruchij.core.daos.videometadata

import cats.data.OptionT
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.videometadata.models.VideoMetadata
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

  override def update(videoMetadataId: String, title: Option[String]): ConnectionIO[Int] =
    OptionT(getById(videoMetadataId))
      .semiflatMap { videoMetadata =>
        sql"UPDATE video_metadata SET title = ${title.getOrElse[String](videoMetadata.title)} WHERE id = $videoMetadataId"
          .update
          .run
      }
      .getOrElse(0)

  override def getById(videoMetadataId: String): ConnectionIO[Option[VideoMetadata]] =
    sql"""
        SELECT
          video_metadata.url,
          video_metadata.id,
          video_metadata.video_site,
          video_metadata.title,
          video_metadata.duration,
          video_metadata.size,
          file_resource.id, file_resource.created_at, file_resource.path, file_resource.media_type, file_resource.size
        FROM video_metadata
        JOIN file_resource ON video_metadata.thumbnail_id = file_resource.id
        WHERE video_metadata.id = $videoMetadataId
    """
      .query[VideoMetadata]
      .option

  override def deleteById(videoMetadataId: String): ConnectionIO[Int] =
    sql"DELETE FROM video_metadata WHERE id = $videoMetadataId"
      .update
      .run
}
