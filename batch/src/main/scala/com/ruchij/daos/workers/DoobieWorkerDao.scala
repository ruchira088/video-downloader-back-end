package com.ruchij.daos.workers

import cats.{Applicative, ApplicativeError}
import cats.data.OptionT
import cats.implicits._
import com.ruchij.daos.doobie.singleUpdate
import com.ruchij.daos.doobie.DoobieCustomMappings._
import com.ruchij.daos.scheduling.SchedulingDao
import com.ruchij.daos.workers.models.Worker
import com.ruchij.exceptions.ResourceNotFoundException
import doobie.free.connection
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import org.joda.time.DateTime

class DoobieWorkerDao(schedulingDao: SchedulingDao[ConnectionIO]) extends WorkerDao[ConnectionIO] {

  override def insert(worker: Worker): ConnectionIO[Int] =
    sql"INSERT INTO worker(id, reserved_at) VALUES(${worker.id}, ${worker.reservedAt})".update.run
      .productR {
        worker.scheduledVideoDownload.fold(connection.pure(0)) { scheduledVideoDownload =>
          sql"""
          INSERT INTO worker_task(worker_id, scheduled_video_id)
          VALUES(${worker.id}, ${scheduledVideoDownload.videoMetadata.id})
        """.update.run
        }
      }

  def getById(workerId: String): ConnectionIO[Worker] =
    sql"SELECT id, reserved_at, task_assigned_at FROM worker WHERE id = $workerId"
      .query[(String, Option[DateTime], Option[DateTime])]
      .unique
      .flatMap {
        case (id, reservedAt, None) => Applicative[ConnectionIO].pure(Worker(id, reservedAt, None))

        case (id, reservedAt, Some(taskAssignedAt)) =>
          sql"SELECT scheduled_video_id FROM worker_task WHERE worker_id = $workerId AND created_at = $taskAssignedAt"
            .query[String]
            .unique
            .flatMap { scheduledVideoId =>
              OptionT(schedulingDao.getById(scheduledVideoId)).getOrElseF {
                ApplicativeError[ConnectionIO, Throwable].raiseError(
                  ResourceNotFoundException(s"ScheduleVideoDownload not found. ID = $scheduledVideoId")
                )
              }
            }
            .map(scheduledVideoDownload => Worker(id, reservedAt, Some(scheduledVideoDownload)))
      }

  override val idleWorker: ConnectionIO[Option[Worker]] =
    OptionT { sql"SELECT id FROM worker WHERE reserved_at IS NULL LIMIT 1".query[String].option }
      .semiflatMap(getById)
      .value

  def reserveWorker(workerId: String, timestamp: DateTime): ConnectionIO[Option[Worker]] =
    singleUpdate {
      sql"UPDATE worker SET reserved_at = ${new DateTime(timestamp)} WHERE id = $workerId".update.run
    }.productR(OptionT.liftF(getById(workerId)))
      .value

  override def assignTask(
    workerId: String,
    scheduledVideoId: String,
    timestamp: DateTime
  ): ConnectionIO[Option[Worker]] =
    singleUpdate {
      sql"""
        INSERT INTO worker_task(worker_id, scheduled_video_id, created_at)
        VALUES ($workerId, $scheduledVideoId, ${new DateTime(timestamp)})
      """.update.run
    }
      .productR {
        singleUpdate {
          sql"""
            UPDATE worker SET task_assigned_at = ${new DateTime(timestamp)} WHERE id = $workerId
          """.update.run
        }
      }
      .productR(OptionT.liftF(getById(workerId)))
      .value

  override def release(workerId: String, timestamp: DateTime): ConnectionIO[Option[Worker]] =
    singleUpdate { sql"UPDATE worker SET reserved_at = NULL, task_assigned_at = NULL WHERE id = $workerId".update.run }
      .productR {
        singleUpdate {
          sql"""
                UPDATE worker_task SET completed_at = ${new DateTime(timestamp)}
                WHERE worker_id = $workerId AND completed_at IS NULL
              """.update.run
        }
      }
      .productR {
        OptionT.liftF(getById(workerId))
      }
      .value
}
