package com.ruchij.daos.snapshot

import cats.effect.Sync
import cats.implicits._
import com.ruchij.daos.doobie.DoobieCustomMappings._
import com.ruchij.daos.resource.FileResourceDao
import com.ruchij.daos.snapshot.models.Snapshot
import doobie.util.transactor.Transactor
import doobie.implicits._

class DoobieSnapshotDao[F[_]: Sync](transactor: Transactor.Aux[F, Unit], fileResourceDao: FileResourceDao[F]) extends SnapshotDao[F] {

  override def insert(snapshot: Snapshot): F[Int] =
    fileResourceDao.insert(snapshot.fileResource)
      .product {
        sql"""
        INSERT INTO video_snapshot(video_id, file_resource_id, video_timestamp)
          VALUES (${snapshot.videoId}, ${snapshot.fileResource.id}, ${snapshot.videoTimestamp})
        """
          .update
          .run
      }
      .map { case (fileResourceResult, videoSnapshotResult) => fileResourceResult + videoSnapshotResult }
      .transact(transactor)

  override def findByVideoId(videoId: String): F[Seq[Snapshot]] =
    sql"""
       SELECT video_snapshot.video_id,
              file_resource.id, file_resource.created_at, file_resource.path,
              file_resource.media_type, file_resource.size,
              video_snapshot.video_timestamp
       FROM video_snapshot
       JOIN file_resource ON video_snapshot.file_resource_id = file_resource.id
       WHERE video_snapshot.video_id = $videoId
     """
      .query[Snapshot]
      .to[Seq]
      .transact(transactor)

}
