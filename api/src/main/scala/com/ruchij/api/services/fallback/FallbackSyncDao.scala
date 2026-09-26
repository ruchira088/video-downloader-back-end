package com.ruchij.api.services.fallback

import cats.Monad
import cats.implicits._
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.permission.VideoPermissionDao
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.models.{RangeValue, ScheduledVideoDownload}
import com.ruchij.core.services.models.{Order, SortBy}
import doobie.free.connection.ConnectionIO
import doobie.free.{connection => FC}
import doobie.implicits._

import java.sql.Connection
import java.time.Instant
import scala.concurrent.duration.FiniteDuration

final case class SyncedVideo(scheduledVideoDownload: ScheduledVideoDownload, userIds: List[String])

trait FallbackSyncDao[F[_]] {
  /** The database's clock, used as every sync message's capturedAt so that all API instances stamp messages from one
    * clock. Postgres returns the transaction's start time, so reading it in the same transaction as the rows it
    * stamps keeps capturedAt no later than the read. */
  def currentTimestamp: F[Instant]

  /** `read` stamped with `currentTimestamp`, both run in one transaction by the caller. */
  def timestamped[A](read: F[A])(implicit monad: Monad[F]): F[(Instant, A)] = currentTimestamp.product(read)

  def findById(videoId: String): F[Option[SyncedVideo]]

  def findAll: F[List[SyncedVideo]]
}

class DoobieFallbackSyncDao(
  schedulingDao: SchedulingDao[ConnectionIO],
  videoPermissionDao: VideoPermissionDao[ConnectionIO],
  pageSize: Int = 500
) extends FallbackSyncDao[ConnectionIO] {

  override val currentTimestamp: ConnectionIO[Instant] = sql"SELECT CURRENT_TIMESTAMP".query[Instant].unique

  /** Under REPEATABLE READ, so every statement of `read` sees the snapshot taken by the first one, which also reads
    * the timestamp. Under the default READ COMMITTED each statement takes a fresh snapshot, so a change committed
    * after the timestamp could be read under it: a message stamped earlier than another could then carry newer data,
    * and lose to the other's older data. A read-only transaction can't fail with a serialization error, and HikariCP
    * restores the pool's isolation level when the connection is returned. */
  override def timestamped[A](read: ConnectionIO[A])(implicit monad: Monad[ConnectionIO]): ConnectionIO[(Instant, A)] =
    FC.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ) *> currentTimestamp.product(read)

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
