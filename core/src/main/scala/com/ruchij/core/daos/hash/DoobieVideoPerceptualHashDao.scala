package com.ruchij.core.daos.hash

import com.ruchij.core.daos.hash.models.VideoPerceptualHash
import doobie.free.connection.ConnectionIO
import doobie.implicits.toSqlInterpolator
import doobie.generic.auto._

import scala.concurrent.duration.FiniteDuration
import com.ruchij.core.daos.doobie.DoobieCustomMappings._

object DoobieVideoPerceptualHashDao extends VideoPerceptualHashDao[ConnectionIO] {

  override val uniqueVideoDurations: ConnectionIO[Set[FiniteDuration]] =
    sql"""
      SELECT DISTINCT video_metadata.duration
        FROM video
        INNER JOIN video_metadata ON video.video_metadata_id = video_metadata.id
    """
      .query[FiniteDuration]
      .to[Set]

  override def getVideoIdsByDuration(duration: FiniteDuration): ConnectionIO[Seq[String]] =
    sql"""
      SELECT video.video_metadata_id
        FROM video
        INNER JOIN video_metadata ON video.video_metadata_id = video_metadata.id
        WHERE video_metadata.duration = $duration
     """
      .query[String]
      .to[Seq]


  override def insert(videoPerceptualHash: VideoPerceptualHash): ConnectionIO[Int] =
    sql"""
      INSERT INTO video_perceptual_hash(video_id, created_at, duration, snapshot_perceptual_hash, snapshot_timestamp)
        VALUES (
          ${videoPerceptualHash.videoId},
          ${videoPerceptualHash.createdAt},
          ${videoPerceptualHash.duration},
          ${videoPerceptualHash.snapshotPerceptualHash.toLong},
          ${videoPerceptualHash.snapshotTimestamp}
        )
    """.update.run

  override def findVideoHashesByDuration(duration: FiniteDuration): ConnectionIO[Seq[VideoPerceptualHash]] =
    sql"""
      SELECT video_id, created_at, duration, snapshot_perceptual_hash, snapshot_timestamp
        FROM video_perceptual_hash
        WHERE duration = $duration
    """
      .query[VideoPerceptualHash]
      .to[Seq]

  override def getByVideoId(videoId: String): ConnectionIO[List[VideoPerceptualHash]] =
    sql"""
      SELECT video_id, created_at, duration, snapshot_perceptual_hash, snapshot_timestamp
        FROM video_perceptual_hash
        WHERE video_id = $videoId
    """
      .query[VideoPerceptualHash]
      .to[List]

  override def deleteByVideoId(videoId: String): ConnectionIO[Int] =
    sql"DELETE FROM video_perceptual_hash WHERE video_id = $videoId"
      .update
      .run
}
