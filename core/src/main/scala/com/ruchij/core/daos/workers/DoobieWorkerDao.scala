package com.ruchij.core.daos.workers

import cats.data.OptionT
import cats.implicits._
import cats.{Applicative, ApplicativeError}
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.doobie.DoobieUtils.singleUpdate
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.models.SchedulingStatus
import com.ruchij.core.daos.workers.models.Worker
import com.ruchij.core.exceptions.ResourceNotFoundException
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import org.joda.time.DateTime

class DoobieWorkerDao(schedulingDao: SchedulingDao[ConnectionIO]) extends WorkerDao[ConnectionIO] {

  override def insert(worker: Worker): ConnectionIO[Int] =
    sql"""
      INSERT INTO worker(id, reserved_at, task_assigned_at)
        VALUES (${worker.id}, ${worker.reservedAt}, ${worker.taskAssignedAt})
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
    sql"SELECT reserved_at, task_assigned_at FROM worker WHERE id = $workerId"
      .query[(Option[DateTime], Option[DateTime])]
      .option
      .flatMap {
        case Some((Some(reservedAt), Some(taskAssignedAt))) =>
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
                Some(Worker(workerId, Some(reservedAt), Some(taskAssignedAt), Some(scheduledVideoDownload)))
            }

        case Some((reservedAt, _)) =>
          Applicative[ConnectionIO].pure(Some(Worker(workerId, reservedAt, None, None)))

        case None => Applicative[ConnectionIO].pure(None)
      }

  override val idleWorker: ConnectionIO[Option[Worker]] =
    OptionT { sql"SELECT id FROM worker WHERE reserved_at IS NULL LIMIT 1".query[String].option }
      .flatMapF(getById)
      .value

  def reserveWorker(workerId: String, timestamp: DateTime): ConnectionIO[Option[Worker]] =
    singleUpdate {
      sql"""
        UPDATE worker
          SET reserved_at = $timestamp
          WHERE id = $workerId AND reserved_at IS NULL
      """
      .update.run
    }.productR(OptionT(getById(workerId))).value

  override def assignTask(
    workerId: String,
    scheduledVideoId: String,
    timestamp: DateTime
  ): ConnectionIO[Option[Worker]] =
    singleUpdate {
      sql"""
          UPDATE scheduled_video
          SET status = ${SchedulingStatus.Active}, last_updated_at = $timestamp
          WHERE video_metadata_id = $scheduledVideoId AND status != ${SchedulingStatus.Active}
        """.update.run
    }
      .productR {
        singleUpdate {
          sql"UPDATE worker SET task_assigned_at = $timestamp WHERE id = $workerId AND task_assigned_at IS NULL"
            .update.run
        }
      }
      .productR {
        singleUpdate {
          sql"""
            INSERT INTO worker_task(worker_id, scheduled_video_id, created_at)
            VALUES ($workerId, $scheduledVideoId, $timestamp)
            """
            .update.run
        }
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
        singleUpdate {
          sql"""
          UPDATE worker_task SET completed_at = $timestamp
          WHERE worker_id = $workerId AND scheduled_video_id = $scheduledVideoId AND created_at = $taskCreatedAt
      """.update.run
        }
      }
      .productR(OptionT(getById(workerId)))
      .value

  override def releaseWorker(workerId: String): ConnectionIO[Option[Worker]] =
    singleUpdate {
      sql"UPDATE worker SET reserved_at = NULL, task_assigned_at = NULL WHERE id = $workerId"
        .update.run
    }
      .productR(OptionT(getById(workerId)))
      .value
}
