package com.ruchij.daos.video

import cats.effect.Bracket
import com.ruchij.daos.video.models.Video
import com.ruchij.daos.doobie.DoobieCustomMappings._
import doobie.implicits._
import doobie.util.transactor.Transactor

class DoobieVideoDao[F[_]: Bracket[*[_], Throwable]](transactor: Transactor.Aux[F, Unit]) extends VideoDao[F] {

  override def insert(video: Video): F[Int] =
    sql"""
      INSERT INTO video (downloaded_at, url, path)
        VALUES (${video.downloadedAt}, ${video.videoMetadata.url}, ${video.path})
    """.update.run.transact(transactor)
}
