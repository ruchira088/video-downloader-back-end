package com.ruchij.daos.workers

import cats.data.OptionT
import cats.effect.{Clock, Sync}
import cats.implicits._
import com.ruchij.daos.doobie.singleUpdate
import com.ruchij.daos.doobie.DoobieCustomMappings._
import com.ruchij.daos.workers.models.WorkerLock
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import doobie.util.transactor.Transactor
import org.joda.time.DateTime

class DoobieWorkerLockDao[F[_]: Clock: Sync](transactor: Transactor.Aux[F, Unit]) extends WorkerLockDao[F] {

  val SELECT_QUERY =
    sql"""
      SELECT 
        worker_lock.id, worker_lock.lock_acquired_at, scheduled_video.scheduled_at, scheduled_video.last_updated_at,
        scheduled_video.download_started_at, video_metadata.url, video_metadata.id, video_metadata.video_site,
        video_metadata.title, video_metadata.duration, video_metadata.size, file_resource.id, file_resource.created_at,
        file_resource.path, file_resource.media_type, file_resource.size, scheduled_video.downloaded_bytes, 
        scheduled_video.completed_at
      FROM worker_lock
      JOIN scheduled_video ON scheduled_video.video_metadata_id = worker_lock.scheduled_video_id
      JOIN video_metadata ON scheduled_video.video_metadata_id = video_metadata.id
      JOIN file_resource ON video_metadata.thumbnail_id = file_resource.id
     """

  val a = sql"SELECT id, lock_acquired_at, scheduled_video_id FROM worker_lock"

  override def insert(workerLock: WorkerLock): F[Int] =
    sql"""
      INSERT INTO worker_lock(id, lock_acquired_at, scheduled_video_id) 
        VALUES(${workerLock.id}, ${workerLock.lockAcquiredAt}, ${workerLock.scheduledVideoDownload.map(_.videoMetadata.id)})
    """
      .update
      .run
      .transact(transactor)

  def getById(workerLockId: String): ConnectionIO[WorkerLock] =
    sql"SELECT id, lock_acquired_at, scheduled_video_id FROM worker_lock WHERE id = $workerLockId"
      .query[(String, Option[DateTime], Option[String])]
      .unique
      .map()

//    (SELECT_QUERY ++ sql"WHERE worker_lock.id = $workerLockId")
//      .query[WorkerLock]
//      .unique

  override val vacantLock: OptionT[F, WorkerLock] =
    OptionT {
      sql"SELECT id FROM worker_lock WHERE lock_acquired_at IS NULL AND scheduled_video_id IS NULL LIMIT 1"
        .query[String]
        .option
    }
      .semiflatMap(getById)
      .transact(transactor)

  def acquireLock(workerLockId: String, timestamp: DateTime): OptionT[F, WorkerLock] =
    singleUpdate {
      sql"UPDATE worker_lock SET lock_acquired_at = $timestamp WHERE id = $workerLockId".update.run
    }
      .productR(OptionT.liftF(getById(workerLockId)))
      .transact(transactor)

  override def setScheduledVideoId(workerLockId: String, scheduledVideoId: String): OptionT[F, WorkerLock] =
    singleUpdate {
      sql"UPDATE worker_lock SET scheduled_video_id = $scheduledVideoId WHERE worker_lock.id = $workerLockId".update.run
    }
      .productR(OptionT.liftF(getById(workerLockId)))
      .transact(transactor)

  override def releaseLock(workerLockId: String): OptionT[F, WorkerLock] =
    singleUpdate {
      sql"UPDATE worker_lock SET lock_acquired_at = NULL AND scheduled_video_id = NULL WHERE worker_lock.id = $workerLockId".update.run
    }
      .productR(OptionT.liftF(getById(workerLockId)))
      .transact(transactor)

}
