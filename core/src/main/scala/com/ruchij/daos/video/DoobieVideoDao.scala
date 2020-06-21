package com.ruchij.daos.video

import com.ruchij.daos.video.models.Video
import com.ruchij.daos.doobie.DoobieCustomMappings._
import com.ruchij.services.models.SortBy
import doobie.implicits._
import doobie.Fragments.whereAndOpt
import doobie.free.connection.ConnectionIO

object DoobieVideoDao extends VideoDao[ConnectionIO] {

  val SELECT_QUERY =
    sql"""
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

  override def search(term: Option[String], pageNumber: Int, pageSize: Int, sortBy: SortBy): ConnectionIO[Seq[Video]] =
    (SELECT_QUERY
      ++ whereAndOpt(term.map(searchTerm => sql"video_metadata.title LIKE ${"%" + searchTerm + "%"}"))
      ++ sql"ORDER BY ${sortByFieldName(sortBy)}"
      ++ sql"LIMIT $pageSize OFFSET ${pageSize * pageNumber}")
      .query[Video]
      .to[Seq]

  override def findById(id: String): ConnectionIO[Option[Video]] =
    (SELECT_QUERY ++ sql"WHERE video_metadata_id = $id").query[Video].option

  val sortByFieldName: SortBy => String = {
    case SortBy.Date => "video_file.created_at"
    case sortBy => s"video_metadata.${sortBy.entryName}"
  }
}
