package com.ruchij.core.daos.snapshot

import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.snapshot.models.Snapshot
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import doobie.util.fragment.Fragment
import doobie.util.fragments.whereAndOpt

object DoobieSnapshotDao extends SnapshotDao[ConnectionIO] {

  override def insert(snapshot: Snapshot): ConnectionIO[Int] =
    sql"""
      INSERT INTO video_snapshot(video_id, file_resource_id, video_timestamp)
        VALUES (${snapshot.videoId}, ${snapshot.fileResource.id}, ${snapshot.videoTimestamp})
    """.update.run

  override def findByVideo(videoId: String, maybeUserId: Option[String]): ConnectionIO[Seq[Snapshot]] =
    (fr"""
       SELECT video_snapshot.video_id,
              file_resource.id, file_resource.created_at, file_resource.path,
              file_resource.media_type, file_resource.size,
              video_snapshot.video_timestamp
       FROM video_snapshot
       JOIN file_resource ON video_snapshot.file_resource_id = file_resource.id
    """ ++ (if (maybeUserId.isEmpty) Fragment.empty else fr"JOIN permission on video_snapshot.video_id = permission.video_id")
      ++
      whereAndOpt(
        Some(fr"video_snapshot.video_id = $videoId"),
        maybeUserId.map(userId => fr"permission.user_id = $userId")
      )
    )
      .query[Snapshot]
      .to[Seq]

  override def hasPermission(snapshotFileResourceId: String, userId: String): ConnectionIO[Boolean] =
    sql"""
      SELECT COUNT(*) FROM video_snapshot
        INNER JOIN permission ON permission.video_id = video_snapshot.video_id
        WHERE
            video_snapshot.file_resource_id = $snapshotFileResourceId AND
            permission.user_id = $userId
    """
      .query[Int]
      .unique
      .map(_ == 1)

  override def isSnapshotFileResource(fileResourceId: String): ConnectionIO[Boolean] =
    sql"SELECT EXISTS(SELECT 1 FROM video_snapshot WHERE file_resource_id = $fileResourceId)".query[Boolean].unique

  override def deleteByVideo(videoId: String): ConnectionIO[Int] =
    sql"DELETE FROM video_snapshot WHERE video_id = $videoId".update.run
}
