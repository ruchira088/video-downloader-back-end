package com.ruchij.daos.snapshot

import com.ruchij.daos.doobie.DoobieCustomMappings._
import com.ruchij.daos.snapshot.models.Snapshot
import doobie.free.connection.ConnectionIO
import doobie.implicits._

object DoobieSnapshotDao extends SnapshotDao[ConnectionIO] {

  override def insert(snapshot: Snapshot): ConnectionIO[Int] =
    sql"""
      INSERT INTO video_snapshot(video_id, file_resource_id, video_timestamp)
        VALUES (${snapshot.videoId}, ${snapshot.fileResource.id}, ${snapshot.videoTimestamp})
    """.update.run

  override def findByVideo(videoId: String): ConnectionIO[Seq[Snapshot]] =
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

  override def deleteByVideo(videoId: String): ConnectionIO[Int] =
    sql"DELETE FROM video_snapshot WHERE video_id = $videoId"
      .update
      .run
}
