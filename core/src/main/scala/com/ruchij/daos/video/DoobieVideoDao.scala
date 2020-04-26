package com.ruchij.daos.video

import cats.effect.Bracket
import cats.implicits._
import com.ruchij.daos.video.models.Video
import com.ruchij.daos.doobie.DoobieCustomMappings._
import com.ruchij.daos.resource.FileResourceDao
import com.ruchij.daos.resource.models.FileResource
import doobie.implicits._
import doobie.util.transactor.Transactor
import doobie.Fragments.whereAndOpt

class DoobieVideoDao[F[_]: Bracket[*[_], Throwable]](
  fileResourceDao: FileResourceDao[F],
  transactor: Transactor.Aux[F, Unit]
) extends VideoDao[F] {

  val SELECT_QUERY =
    sql"""
       SELECT
        video_metadata.url, 
        video_metadata.key, 
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
      JOIN video_metadata ON video.video_metadata_key = video_metadata.key
      JOIN file_resource AS thumbnail ON video_metadata.thumbnail = thumbnail.id
      JOIN file_resource AS video_file ON video.file_resource_id = video_file.id
    """

  override def insert(videoMetadataKey: String, fileResource: FileResource): F[Int] =
    fileResourceDao
      .insert(fileResource)
      .product {
        sql"INSERT INTO video (video_metadata_key, file_resource_id) VALUES ($videoMetadataKey, ${fileResource.id})".update.run
      }
      .map { case (fileInsertResult, videoInsertResult) => fileInsertResult + videoInsertResult }
      .transact(transactor)

  override def search(term: Option[String], pageNumber: Int, pageSize: Int): F[Seq[Video]] =
    (SELECT_QUERY
      ++ whereAndOpt(term.map(searchTerm => sql"video_metadata.title LIKE ${"%" + searchTerm + "%"}"))
      ++ sql"OFFSET ${pageSize * pageNumber} LIMIT $pageSize")
      .query[Video]
      .to[Seq]
      .transact(transactor)

  override def findByKey(key: String): F[Option[Video]] =
    (SELECT_QUERY ++ sql"WHERE video_metadata_key = $key").query[Video].option.transact(transactor)
}
