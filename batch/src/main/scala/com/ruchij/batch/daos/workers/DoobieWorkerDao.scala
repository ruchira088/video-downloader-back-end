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
import org.joda.time.DateTime

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
      .query[(WorkerStatus, Option[DateTime], Option[DateTime], Option[String], Option[String])]
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

  def reserveWorker(workerId: String, owner: String, timestamp: DateTime): ConnectionIO[Option[Worker]] =
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
    timestamp: DateTime
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

  override def releaseWorker(workerId: String): ConnectionIO[Option[Worker]] =
    sql"""
       UPDATE worker
        SET
          task_assigned_at = NULL,
          heart_beat_at = NULL,
          scheduled_video_id = NULL,
          status = ${WorkerStatus.Available},
          owner = NULL
        WHERE id = $workerId
     """.update.run.singleUpdate
      .productR(OptionT(getById(workerId)))
      .value

  override def updateHeartBeat(workerId: String, timestamp: DateTime): ConnectionIO[Option[Worker]] =
    sql"UPDATE worker SET heart_beat_at = $timestamp WHERE id = $workerId".update.run.singleUpdate
      .productR(OptionT(getById(workerId)))
      .value

  override def cleanUpStaleWorkers(heartBeatBefore: DateTime): ConnectionIO[Seq[Worker]] =
    sql"""
      SELECT id FROM worker
        WHERE
          heart_beat_at < $heartBeatBefore OR (task_assigned_at IS NULL AND status = ${WorkerStatus.Active})
   """.query[String]
      .to[Seq]
      .flatMap { staleWorkerIds =>
        staleWorkerIds
          .traverse(getById)
          .map(_.flatten)
          .productL {
            NonEmptyList
              .fromFoldable(staleWorkerIds)
              .fold(Applicative[ConnectionIO].pure(0)) { nonEmptyStaleWorkerIds =>
                (fr"""
            UPDATE worker
              SET
                task_assigned_at = NULL,
                heart_beat_at = NULL,
                status = ${WorkerStatus.Available},
                scheduled_video_id = NULL,
                owner = NULL
              WHERE""" ++
                  doobie.Fragments.in(fr"id", nonEmptyStaleWorkerIds)).update.run
              }
          }
      }

  override def updateWorkerStatuses(workerStatus: WorkerStatus): ConnectionIO[Seq[Worker]] =
    sql"""
        UPDATE worker
            SET
              task_assigned_at = NULL,
              heart_beat_at = NULL,
              scheduled_video_id = NULL,
              owner = NULL,
              status = $workerStatus
    """.update.run
      .productR { sql"SELECT id FROM worker".query[String].to[Seq] }
      .flatMap { workerIds =>
        workerIds.traverse(getById).map(_.flatten)
      }

  override def clearScheduledVideoDownload(scheduledVideoDownloadId: String): ConnectionIO[Int] =
    sql"UPDATE worker SET scheduled_video_id = null WHERE scheduled_video_id = $scheduledVideoDownloadId".update.run
}
