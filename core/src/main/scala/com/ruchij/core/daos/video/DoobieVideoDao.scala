package com.ruchij.core.daos.video

import cats.data.NonEmptyList
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.doobie.DoobieUtils.{ordering, sortByFieldName}
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.services.models.{Order, SortBy}
import doobie.Fragments.{in, whereAndOpt}
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import doobie.util.fragment.Fragment
import org.http4s.Uri

object DoobieVideoDao extends VideoDao[ConnectionIO] {

  val SelectQuery =
    fr"""
       SELECT
        video_metadata.url,
        video_metadata.id,
        video_metadata.video_site,
        video_metadata.title,
        video_metadata.duration,
        video_metadata.size,
        thumbnail.id, thumbnail.created_at, thumbnail.path, thumbnail.media_type, thumbnail.size,
        video_file.id,
        video_file.created_at,
        video_file.path,
        video_file.media_type,
        video_file.size
      FROM video
      JOIN video_metadata ON video.video_metadata_id = video_metadata.id
      JOIN file_resource AS thumbnail ON video_metadata.thumbnail_id = thumbnail.id
      JOIN file_resource AS video_file ON video.file_resource_id = video_file.id
    """

  override def insert(videoMetadataId: String, videoFileResourceId: String): ConnectionIO[Int] =
    sql"INSERT INTO video (video_metadata_id, file_resource_id) VALUES ($videoMetadataId, $videoFileResourceId)".update.run

  override def search(
    term: Option[String],
    videoUrls: Option[NonEmptyList[Uri]],
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order
  ): ConnectionIO[Seq[Video]] =
    (SelectQuery
      ++ fr"ORDER BY"
      ++ videoSortByFieldName(sortBy)
      ++ ordering(order)
      ++
        whereAndOpt(
          term.map(searchTerm => fr"video_metadata.title LIKE ${"%" + searchTerm + "%"}"),
          videoUrls.map(urls => in(fr"video_metadata.url", urls))
        )
      ++ fr"LIMIT $pageSize OFFSET ${pageSize * pageNumber}")
      .query[Video]
      .to[Seq]

  override def findById(id: String): ConnectionIO[Option[Video]] =
    (SelectQuery ++ fr"WHERE video_metadata_id = $id").query[Video].option

  val videoSortByFieldName: SortBy => Fragment =
    sortByFieldName.orElse {
      case SortBy.Date => fr"video_file.created_at"
    }

  override def deleteById(videoId: String): ConnectionIO[Int] =
    sql"DELETE FROM video WHERE video_metadata_id = $videoId".update.run
}
