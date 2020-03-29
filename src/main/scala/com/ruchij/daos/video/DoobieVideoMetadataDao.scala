package com.ruchij.daos.video

import cats.effect.Bracket
import com.ruchij.daos.video.models.VideoMetadata
import com.ruchij.daos.doobie.DoobieCustomMappings._
import doobie.implicits._
import doobie.util.transactor.Transactor

class DoobieVideoMetadataDao[F[_]: Bracket[*[_], Throwable]](transactor: Transactor.Aux[F, Unit]) extends VideoMetadataDao[F] {

  override def insert(videoMetadata: VideoMetadata): F[Int] =
    sql"""
      INSERT INTO video_metadata (url, video_site, title, duration, size, thumbnail)
        VALUES (
          ${videoMetadata.url},
          ${videoMetadata.videoSite},
          ${videoMetadata.title},
          ${videoMetadata.duration},
          ${videoMetadata.size},
          ${videoMetadata.thumbnail}
        )
    """
      .update.run.transact(transactor)
}
