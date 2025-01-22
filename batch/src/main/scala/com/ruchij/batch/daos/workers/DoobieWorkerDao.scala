package com.ruchij.batch.daos.workers

import cats.data.OptionT
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
    sql"SELECT id FROM worker".query[String].to[Seq]
      .flatMap { ids =>
        ids.toList.traverse { id => getById(id).map(_.toList) }
      }
      .map(_.flatten)

  override def setStatus(workerId: String, workerStatus: WorkerStatus): ConnectionIO[Int] =
    sql"UPDATE worker SET status = $workerStatus WHERE id = $workerId".update.run

  override def insert(worker: Worker): ConnectionIO[Int] =
    sql"""
      INSERT INTO worker(id, status, heart_beat_at, task_assigned_at, scheduled_video_id)
        VALUES (
          ${worker.id},
          ${worker.status},
          ${worker.heartBeatAt},
          ${worker.taskAssignedAt},
          ${worker.scheduledVideoDownload.map(_.videoMetadata.id)}
        )
    """
      .update.run
      .flatMap { result =>
        worker.scheduledVideoDownload.fold(Applicative[ConnectionIO].pure(result)) { scheduledVideoDownload =>
          sql"""
            INSERT INTO worker_task(worker_id, created_at, scheduled_video_id)
            VALUES(${worker.id}, ${worker.taskAssignedAt}, ${scheduledVideoDownload.videoMetadata.id})
          """.update.run.map(_ + result)
        }
      }

  override def getById(workerId: String): ConnectionIO[Option[Worker]] =
    sql"SELECT status, task_assigned_at, heart_beat_at, scheduled_video_id FROM worker WHERE id = $workerId"
      .query[(WorkerStatus, Option[DateTime], Option[DateTime], Option[String])]
      .option
      .flatMap {
        case Some((WorkerStatus.Active, Some(taskAssignedAt), heartBeatAt, Some(scheduledVideoId))) =>
          OptionT(schedulingDao.getById(scheduledVideoId, None)).getOrElseF {
              ApplicativeError[ConnectionIO, Throwable].raiseError {
                ResourceNotFoundException(s"ScheduleVideoDownload not found. ID = $scheduledVideoId")
              }
            }
            .map {
              scheduledVideoDownload =>
                Some(models.Worker(workerId, WorkerStatus.Active, Some(taskAssignedAt), heartBeatAt, Some(scheduledVideoDownload)))
            }

        case Some((status, reservedAt, heartBeat, None)) =>
          Applicative[ConnectionIO].pure(Some(models.Worker(workerId, status, reservedAt, heartBeat, None)))

        case _ => Applicative[ConnectionIO].pure(None)
      }

  override val idleWorker: ConnectionIO[Option[Worker]] =
    OptionT { sql"SELECT id FROM worker WHERE status = ${WorkerStatus.Available} LIMIT 1 ORDER BY id".query[String].option }
      .flatMapF(getById)
      .value

  def reserveWorker(workerId: String, timestamp: DateTime): ConnectionIO[Option[Worker]] =
      sql"""
        UPDATE worker
          SET status = ${WorkerStatus.Reserved}
          WHERE id = $workerId AND status = ${WorkerStatus.Available}
      """
      .update.run
      .singleUpdate
      .productR(OptionT(getById(workerId)))
      .value

  override def assignTask(
    workerId: String,
    scheduledVideoId: String,
    timestamp: DateTime
  ): ConnectionIO[Option[Worker]] = {
      sql"""
        UPDATE scheduled_video
            SET status = ${SchedulingStatus.Active}, last_updated_at = $timestamp
            WHERE video_metadata_id = $scheduledVideoId AND status != ${SchedulingStatus.Active}
      """
        .update
        .run
        .one
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
                heart_beat_at IS NULL
          """
            .update
            .run
            .one
        }
        .productR {
          sql"""
            INSERT INTO worker_task(worker_id, scheduled_video_id, created_at)
            VALUES ($workerId, $scheduledVideoId, $timestamp)
          """
            .update
            .run
            .one
        }
        .productR(getById(workerId))
  }

  override def completeTask(
    workerId: String,
    scheduledVideoId: String,
    timestamp: DateTime
  ): ConnectionIO[Option[Worker]] =
    OptionT {
      sql"SELECT task_assigned_at FROM worker WHERE id = $workerId"
        .query[DateTime]
        .option
    }
      .flatMap { taskCreatedAt =>
        sql"""
          UPDATE worker_task SET completed_at = $timestamp
            WHERE worker_id = $workerId AND scheduled_video_id = $scheduledVideoId AND created_at = $taskCreatedAt
        """
          .update
          .run
          .singleUpdate
      }
      .productR(OptionT(getById(workerId)))
      .value

  override def releaseWorker(workerId: String): ConnectionIO[Option[Worker]] =
    sql"""
       UPDATE worker
        SET
          task_assigned_at = NULL,
          heart_beat_at = NULL,
          scheduled_video_id = NULL,
          status = ${WorkerStatus.Available}
        WHERE id = $workerId
     """
      .update
      .run
      .singleUpdate
      .productR(OptionT(getById(workerId)))
      .value

  override def updateHeartBeat(workerId: String, timestamp: DateTime): ConnectionIO[Option[Worker]] =
      sql"UPDATE worker SET heart_beat_at = $timestamp WHERE id = $workerId"
        .update
        .run
        .singleUpdate
        .productR(OptionT(getById(workerId)))
        .value

  override def cleanUpStaleWorkers(heartBeatBefore: DateTime): ConnectionIO[Int] =
    sql"""
      UPDATE worker
        SET
          task_assigned_at = NULL,
          heart_beat_at = NULL,
          status = ${WorkerStatus.Available},
          scheduled_video_id = NULL
        WHERE heart_beat_at < $heartBeatBefore OR (task_assigned_at IS NULL AND status = ${WorkerStatus.Active})
    """
      .update
      .run

  override def updateWorkerStatuses(workerStatus: WorkerStatus): ConnectionIO[Seq[Worker]] =
    sql"""
        UPDATE worker
            SET
              task_assigned_at = NULL,
              heart_beat_at = NULL,
              scheduled_video_id = NULL,
              status = $workerStatus
    """
      .update
      .run
      .productR { sql"SELECT id FROM worker".query[String].to[Seq] }
      .flatMap {
        workerIds => workerIds.traverse(getById).map(_.flatten)
      }
}
