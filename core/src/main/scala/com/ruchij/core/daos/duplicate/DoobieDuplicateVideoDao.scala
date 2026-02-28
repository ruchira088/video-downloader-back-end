package com.ruchij.core.daos.duplicate

import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.duplicate.models.DuplicateVideo
import doobie.ConnectionIO
import doobie.implicits._

object DoobieDuplicateVideoDao extends DuplicateVideoDao[ConnectionIO] {

  override def insert(duplicateVideo: DuplicateVideo): ConnectionIO[Int] =
    sql"""
      INSERT INTO duplicate_video(video_id, duplicate_group_id, created_at)
        VALUES (
          ${duplicateVideo.videoId},
          ${duplicateVideo.duplicateGroupId},
          ${duplicateVideo.createdAt}
        )
    """.update.run

  override def delete(videoId: String): ConnectionIO[Int] =
    sql"DELETE FROM duplicate_video WHERE video_id = $videoId"
      .update
      .run

  override def findByVideoId(videoId: String): ConnectionIO[Option[DuplicateVideo]] =
    sql"SELECT video_id, duplicate_group_id, created_at FROM duplicate_video WHERE video_id = $videoId"
      .query[DuplicateVideo]
      .option

  override def findByDuplicateGroupId(duplicateGroupId: String): ConnectionIO[Seq[DuplicateVideo]] =
    sql"SELECT video_id, duplicate_group_id, created_at FROM duplicate_video WHERE duplicate_group_id = $duplicateGroupId"
      .query[DuplicateVideo]
      .to[Seq]

  override def getAll(offset: Int, limit: Int): ConnectionIO[Seq[DuplicateVideo]] =
    sql"SELECT video_id, duplicate_group_id, created_at FROM duplicate_video LIMIT $limit OFFSET $offset"
      .query[DuplicateVideo]
      .to[Seq]

  override val duplicateGroupIds: ConnectionIO[Seq[String]] =
    sql"SELECT DISTINCT duplicate_group_id FROM duplicate_video"
      .query[String]
      .to[Seq]
}
