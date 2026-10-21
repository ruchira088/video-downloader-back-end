package com.ruchij.batch.daos.workers

import cats.data.{NonEmptyList, OptionT}
import cats.implicits._
import cats.{Applicative, ApplicativeError}
import com.ruchij.batch.daos.workers.models.Worker
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.doobie.DoobieUtils.SingleUpdateOps
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.models.SchedulingStatus
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.exceptions.ResourceNotFoundException
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import doobie.util.fragment.Fragment

import java.time.Instant

class DoobieWorkerDao(schedulingDao: SchedulingDao[ConnectionIO]) extends WorkerDao[ConnectionIO] {

  override val all: ConnectionIO[Seq[Worker]] =
    sql"SELECT id FROM worker"
      .query[String]
      .to[Seq]
      .flatMap { ids =>
        ids.toList.traverse { id =>
          getById(id).map(_.toList)
        }
      }
      .map(_.flatten)

  override def setStatus(workerId: String, workerStatus: WorkerStatus): ConnectionIO[Int] =
    sql"UPDATE worker SET status = $workerStatus WHERE id = $workerId".update.run

  override def insert(worker: Worker): ConnectionIO[Int] =
    sql"""
      INSERT INTO worker(id, status, heart_beat_at, task_assigned_at, scheduled_video_id, owner)
        VALUES (
          ${worker.id},
          ${worker.status},
          ${worker.heartBeatAt},
          ${worker.taskAssignedAt},
          ${worker.scheduledVideoDownload.map(_.videoMetadata.id)},
          ${worker.owner}
        )
    """.update.run

  override def getById(workerId: String): ConnectionIO[Option[Worker]] =
    sql"SELECT status, task_assigned_at, heart_beat_at, scheduled_video_id, owner FROM worker WHERE id = $workerId"
      .query[(WorkerStatus, Option[Instant], Option[Instant], Option[String], Option[String])]
      .option
      .flatMap {
        case Some((status, taskAssignedAt, heartBeatAt, Some(scheduledVideoId), owner)) =>
          OptionT(schedulingDao.getById(scheduledVideoId, None))
            .getOrElseF {
              ApplicativeError[ConnectionIO, Throwable].raiseError {
                ResourceNotFoundException(s"ScheduleVideoDownload not found. ID = $scheduledVideoId")
              }
            }
            .map { scheduledVideoDownload =>
              Some(models.Worker(workerId, status, taskAssignedAt, heartBeatAt, Some(scheduledVideoDownload), owner))
            }

        case Some((status, reservedAt, heartBeat, None, owner)) =>
          Applicative[ConnectionIO].pure(Some(models.Worker(workerId, status, reservedAt, heartBeat, None, owner)))

        case None => Applicative[ConnectionIO].pure(None)
      }

  override val idleWorker: ConnectionIO[Option[Worker]] =
    OptionT {
      sql"SELECT id FROM worker WHERE status = ${WorkerStatus.Available} ORDER BY id LIMIT 1".query[String].option
    }.flatMapF(getById).value

  def reserveWorker(workerId: String, owner: String, timestamp: Instant): ConnectionIO[Option[Worker]] =
    sql"""
        UPDATE worker
          SET
            status = ${WorkerStatus.Reserved},
            heart_beat_at = $timestamp,
            owner = $owner
          WHERE id = $workerId AND status = ${WorkerStatus.Available}
      """.update.run.singleUpdate
      .productR(OptionT(getById(workerId)))
      .value

  override def assignTask(
    workerId: String,
    scheduledVideoId: String,
    owner: String,
    timestamp: Instant
  ): ConnectionIO[Option[Worker]] = {
    sql"""
        UPDATE scheduled_video
            SET status = ${SchedulingStatus.Active}, last_updated_at = $timestamp
            WHERE video_metadata_id = $scheduledVideoId AND status != ${SchedulingStatus.Active}
      """.update.run.one
      .productR {
        sql"""
            UPDATE worker
              SET
                task_assigned_at = $timestamp,
                heart_beat_at = $timestamp,
                scheduled_video_id = $scheduledVideoId,
                status = ${WorkerStatus.Active}
              WHERE
                id = $workerId AND
                task_assigned_at IS NULL AND
                status = ${WorkerStatus.Reserved} AND
                owner = $owner
          """.update.run.one
      }
      .productR(getById(workerId))
  }

  /**
    * Only the owner may release a worker. If the worker was freed as stale and claimed by another instance in the
    * meantime, releasing it by id alone would wipe out the other instance's claim.
    */
  override def releaseWorker(workerId: String, owner: String): ConnectionIO[Option[Worker]] =
    sql"""
       UPDATE worker
        SET
          task_assigned_at = NULL,
          heart_beat_at = NULL,
          scheduled_video_id = NULL,
          status = ${WorkerStatus.Available},
          owner = NULL
        WHERE id = $workerId AND owner = $owner
     """.update.run.singleUpdate
      .productR(OptionT(getById(workerId)))
      .value

  override def updateHeartBeat(workerId: String, owner: String, timestamp: Instant): ConnectionIO[Option[Worker]] =
    sql"UPDATE worker SET heart_beat_at = $timestamp WHERE id = $workerId AND owner = $owner".update.run.singleUpdate
      .productR(OptionT(getById(workerId)))
      .value

  private def staleWorkerCondition(heartBeatBefore: Instant): Fragment =
    fr"""
      status != ${WorkerStatus.Deleted} AND
        (heart_beat_at < $heartBeatBefore OR (task_assigned_at IS NULL AND status = ${WorkerStatus.Active}))
    """

  override def cleanUpStaleWorkers(heartBeatBefore: Instant): ConnectionIO[Seq[Worker]] =
    (fr"SELECT id FROM worker WHERE" ++ staleWorkerCondition(heartBeatBefore))
      .query[String]
      .to[Seq]
      .flatMap { staleWorkerIds =>
        staleWorkerIds
          .traverse(getById)
          .map(_.flatten)
          .productL {
            NonEmptyList
              .fromFoldable(staleWorkerIds)
              .fold(Applicative[ConnectionIO].pure(0)) { nonEmptyStaleWorkerIds =>
                // The stale condition is repeated, so that a worker that has been claimed again since the SELECT is
                // left alone
                (fr"""
            UPDATE worker
              SET
                task_assigned_at = NULL,
                heart_beat_at = NULL,
                status = ${WorkerStatus.Available},
                scheduled_video_id = NULL,
                owner = NULL
              WHERE""" ++
                  doobie.Fragments.in(fr"id", nonEmptyStaleWorkerIds) ++
                  fr"AND" ++ staleWorkerCondition(heartBeatBefore)).update.run
              }
          }
      }

  /**
    * Every batch instance applies the same worker status update, at different times, so the update must be
    * idempotent and must not undo the work that other instances have done since.
    *
    *  - Pausing pauses every worker that is not already paused, or deleted. Its owner is removed, so the instance
    *    running a download on it can neither release it nor keep it alive, and the worker stays paused.
    *  - Resuming only resumes the paused workers. The workers that have been claimed since, by an instance that has
    *    already resumed, are left alone.
    *  - Deleted workers are never brought back.
    */
  override def updateWorkerStatuses(workerStatus: WorkerStatus): ConnectionIO[Seq[Worker]] = {
    val maybeCondition: Option[Fragment] =
      workerStatus match {
        case WorkerStatus.Paused =>
          Some(fr"status NOT IN (${WorkerStatus.Paused}, ${WorkerStatus.Deleted})")

        case WorkerStatus.Available => Some(fr"status = ${WorkerStatus.Paused}")

        case _ => None
      }

    maybeCondition
      .fold(Applicative[ConnectionIO].pure(0)) { condition =>
        (fr"""
          UPDATE worker
            SET
              task_assigned_at = NULL,
              heart_beat_at = NULL,
              scheduled_video_id = NULL,
              owner = NULL,
              status = $workerStatus
            WHERE""" ++ condition).update.run
      }
      .productR { sql"SELECT id FROM worker".query[String].to[Seq] }
      .flatMap { workerIds =>
        workerIds.traverse(getById).map(_.flatten)
      }
  }

  override def clearScheduledVideoDownload(scheduledVideoDownloadId: String): ConnectionIO[Int] =
    sql"UPDATE worker SET scheduled_video_id = null WHERE scheduled_video_id = $scheduledVideoDownloadId".update.run
}
