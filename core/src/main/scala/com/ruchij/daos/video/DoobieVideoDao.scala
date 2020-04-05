package com.ruchij.daos.video

import cats.effect.Bracket
import com.ruchij.daos.video.models.Video
import com.ruchij.daos.doobie.DoobieCustomMappings._
import doobie.implicits._
import doobie.util.transactor.Transactor
import doobie.Fragments.whereAndOpt

class DoobieVideoDao[F[_]: Bracket[*[_], Throwable]](transactor: Transactor.Aux[F, Unit]) extends VideoDao[F] {

  val SELECT_QUERY =
    sql"""
       SELECT
        video.downloaded_at, video_metadata.url, video_metadata.video_site, video_metadata.title,
        video_metadata.duration, video_metadata.size, video_metadata.thumbnail , video.path
      FROM video
      JOIN video_metadata ON video.url = video_metadata.url
    """

  override def insert(video: Video): F[Int] =
    sql"""
      INSERT INTO video (downloaded_at, url, path)
        VALUES (${video.downloadedAt}, ${video.videoMetadata.url}, ${video.path})
    """.update.run.transact(transactor)

  override def search(term: Option[String], pageNumber: Int, pageSize: Int): F[Seq[Video]] =
    (SELECT_QUERY
      ++ whereAndOpt(term.map(searchTerm => sql"title LIKE %{$searchTerm}%"))
      ++ sql"OFFSET ${pageSize * pageNumber} LIMIT $pageSize")
    .query[Video]
    .to[Seq]
    .transact(transactor)
}
