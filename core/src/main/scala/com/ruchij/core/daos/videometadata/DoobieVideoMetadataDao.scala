package com.ruchij.core.daos.videometadata

import cats.Applicative
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.videometadata.models.VideoMetadata
import doobie.ConnectionIO
import doobie.implicits._
import doobie.util.fragment.Fragment
import doobie.util.fragments.setOpt
import org.http4s.Uri

import scala.concurrent.duration.FiniteDuration

object DoobieVideoMetadataDao extends VideoMetadataDao[ConnectionIO] {

  val SelectQuery =
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
    """

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

  override def update(
    videoMetadataId: String,
    maybeTitle: Option[String],
    maybeSize: Option[Long],
    maybeDuration: Option[FiniteDuration]
  ): ConnectionIO[Int] = {
    val setValues: List[Option[Fragment]] =
      List(
        maybeTitle.map(title => fr"title = $title"),
        maybeSize.map(size => fr"size = $size"),
        maybeDuration.map(duration => fr"duration = $duration")
      )

    if (setValues.flatMap(_.toList).nonEmpty)
      (fr"UPDATE video_metadata" ++ setOpt(setValues: _*) ++ fr"WHERE id = $videoMetadataId").update.run
    else Applicative[ConnectionIO].pure(0)
  }

  override def findById(videoMetadataId: String): ConnectionIO[Option[VideoMetadata]] =
    (SelectQuery ++ fr"WHERE video_metadata.id = $videoMetadataId")
      .query[VideoMetadata]
      .option

  override def findByUrl(uri: Uri): ConnectionIO[Option[VideoMetadata]] =
    (SelectQuery ++ fr"WHERE video_metadata.url = $uri")
      .query[VideoMetadata]
      .option

  override def deleteById(videoMetadataId: String): ConnectionIO[Int] =
    sql"DELETE FROM video_metadata WHERE id = $videoMetadataId".update.run
}
