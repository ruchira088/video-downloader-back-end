package com.ruchij.batch.daos.detection

import com.ruchij.batch.daos.detection.models.VideoPerceptualHash
import doobie.free.connection.ConnectionIO
import doobie.implicits.toSqlInterpolator

import scala.concurrent.duration.FiniteDuration
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import java.time.Instant

object DoobieDuplicateDetectionDao extends DuplicateDetectionDao[ConnectionIO] {

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
      SELECT video.id
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
      .query[(String, Instant, FiniteDuration, Long, FiniteDuration)]
      .map { case (videoId, createdAt, dur, hash, snapshotTs) =>
        VideoPerceptualHash(videoId, createdAt, dur, BigInt(hash), snapshotTs)
      }
      .to[Seq]
}
