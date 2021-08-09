package com.ruchij.core.daos.workers

import cats.data.OptionT
import cats.implicits._
import cats.{Applicative, ApplicativeError}
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.doobie.DoobieUtils.SingleUpdateOps
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.workers.models.{Worker, WorkerStatus}
import com.ruchij.core.exceptions.ResourceNotFoundException
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import org.joda.time.DateTime

class DoobieWorkerDao(schedulingDao: SchedulingDao[ConnectionIO]) extends WorkerDao[ConnectionIO] {

  override def insert(worker: Worker): ConnectionIO[Int] =
    sql"""
      INSERT INTO worker(id, status, heart_beat_at, task_assigned_at)
        VALUES (${worker.id}, ${worker.status}, ${worker.heartBeatAt}, ${worker.taskAssignedAt})
    """
      .update.run
      .flatMap { result =>
        worker.scheduledVideoDownload.fold(Applicative[ConnectionIO].pure(result)) { scheduledVideoDownload =>
          sql"""
            INSERT INTO worker_task(worker_id, scheduled_video_id)
            VALUES(${worker.id}, ${scheduledVideoDownload.videoMetadata.id})
          """.update.run.map(_ + result)
        }
      }

  override def getById(workerId: String): ConnectionIO[Option[Worker]] =
    sql"SELECT status, task_assigned_at, heart_beat_at FROM worker WHERE id = $workerId"
      .query[(WorkerStatus, Option[DateTime], Option[DateTime])]
      .option
      .flatMap {
        case Some((WorkerStatus.Active, Some(taskAssignedAt), heartBeatAt)) =>
          sql"SELECT scheduled_video_id FROM worker_task WHERE worker_id = $workerId AND created_at = $taskAssignedAt"
            .query[String]
            .unique
            .flatMap { scheduledVideoId =>
              OptionT(schedulingDao.getById(scheduledVideoId)).getOrElseF {
                ApplicativeError[ConnectionIO, Throwable].raiseError {
                  ResourceNotFoundException(s"ScheduleVideoDownload not found. ID = $scheduledVideoId")
                }
              }
            }
            .map {
              scheduledVideoDownload =>
                Some(Worker(workerId, WorkerStatus.Active, Some(taskAssignedAt), heartBeatAt, Some(scheduledVideoDownload)))
            }

        case Some((status, reservedAt, heartBeat)) =>
          Applicative[ConnectionIO].pure(Some(Worker(workerId, status, reservedAt, heartBeat, None)))

        case None => Applicative[ConnectionIO].pure(None)
      }

  override val idleWorker: ConnectionIO[Option[Worker]] =
    OptionT { sql"SELECT id FROM worker WHERE status = ${WorkerStatus.Available} LIMIT 1".query[String].option }
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
  ): ConnectionIO[Option[Worker]] =
      sql"""
          UPDATE worker
            SET task_assigned_at = $timestamp, heart_beat_at = $timestamp, status = ${WorkerStatus.Active}
            WHERE
                  id = $workerId AND
                  task_assigned_at IS NULL AND
                  status = ${WorkerStatus.Reserved} AND
                  heart_beat_at IS NULL
      """
        .update
        .run
        .singleUpdate
        .productR {
          sql"""
            INSERT INTO worker_task(worker_id, scheduled_video_id, created_at)
            VALUES ($workerId, $scheduledVideoId, $timestamp)
          """
            .update
            .run
            .singleUpdate
        }
        .productR(OptionT(getById(workerId)))
        .value

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
        SET task_assigned_at = NULL, heart_beat_at = NULL, status = ${WorkerStatus.Available}
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
        SET task_assigned_at = NULL, heart_beat_at = NULL, status = ${WorkerStatus.Available}
        WHERE heart_beat_at < $heartBeatBefore OR (task_assigned_at IS NULL AND status = ${WorkerStatus.Active})
    """
      .update
      .run
}
