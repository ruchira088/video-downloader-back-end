package com.ruchij.api.services.fallback

import cats.implicits._
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.permission.VideoPermissionDao
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.models.{RangeValue, ScheduledVideoDownload}
import com.ruchij.core.services.models.{Order, SortBy}
import doobie.free.connection.ConnectionIO
import doobie.implicits._

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

final case class SyncedVideo(scheduledVideoDownload: ScheduledVideoDownload, userIds: List[String])

trait FallbackSyncDao[F[_]] {
  /** The database's clock, used as every sync message's capturedAt so that all API instances stamp messages from one
    * clock. Postgres returns the transaction's start time, so reading it in the same transaction as the rows it
    * stamps keeps capturedAt no later than the read. */
  def currentTimestamp: F[Instant]

  def findById(videoId: String): F[Option[SyncedVideo]]

  def findAll: F[List[SyncedVideo]]
}

class DoobieFallbackSyncDao(
  schedulingDao: SchedulingDao[ConnectionIO],
  videoPermissionDao: VideoPermissionDao[ConnectionIO],
  pageSize: Int = 500
) extends FallbackSyncDao[ConnectionIO] {

  override val currentTimestamp: ConnectionIO[Instant] = sql"SELECT CURRENT_TIMESTAMP".query[Instant].unique

  override def findById(videoId: String): ConnectionIO[Option[SyncedVideo]] =
    schedulingDao.getById(videoId, None).flatMap {
      _.traverse { video =>
        videoPermissionDao
          .find(None, Some(videoId))
          .map(permissions => SyncedVideo(video, permissions.map(_.userId).toList))
      }
    }

  override def findAll: ConnectionIO[List[SyncedVideo]] =
    for {
      videos <- allVideos(0, Vector.empty)
      permissions <- videoPermissionDao.find(None, None)
      userIdsByVideo = permissions.groupMap(_.scheduledVideoDownloadId)(_.userId)
    } yield
      videos.toList.map { video =>
        SyncedVideo(video, userIdsByVideo.getOrElse(video.videoMetadata.id, Seq.empty).toList)
      }

  private def allVideos(
    pageNumber: Int,
    accumulated: Vector[ScheduledVideoDownload]
  ): ConnectionIO[Vector[ScheduledVideoDownload]] =
    schedulingDao
      .search(
        None,
        None,
        RangeValue.all[FiniteDuration],
        RangeValue.all[Long],
        pageNumber,
        pageSize,
        SortBy.Date,
        Order.Ascending,
        None,
        None,
        None
      )
      .flatMap { page =>
        val next = accumulated ++ page
        if (page.size < pageSize) next.pure[ConnectionIO] else allVideos(pageNumber + 1, next)
      }
}
