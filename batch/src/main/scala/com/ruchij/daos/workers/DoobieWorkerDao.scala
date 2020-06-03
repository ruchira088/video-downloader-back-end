package com.ruchij.daos.workers

import java.util.concurrent.TimeUnit

import cats.{Applicative, ApplicativeError}
import cats.data.OptionT
import cats.effect.{Clock, Sync}
import cats.implicits._
import com.ruchij.daos.doobie.singleUpdate
import com.ruchij.daos.doobie.DoobieCustomMappings._
import com.ruchij.daos.scheduling.SchedulingDao
import com.ruchij.daos.workers.models.Worker
import com.ruchij.exceptions.ResourceNotFoundException
import doobie.free.connection
import doobie.implicits._
import doobie.util.transactor.Transactor
import org.joda.time.DateTime

class DoobieWorkerDao[F[_]: Clock: Sync](schedulingDao: SchedulingDao[F], transactor: Transactor.Aux[F, Unit])
  extends WorkerDao[F] {

  override def insert(worker: Worker): F[Int] =
    sql"INSERT INTO worker(id, reserved_at) VALUES(${worker.id}, ${worker.reservedAt})".update.run
      .productR {
        worker.scheduledVideoDownload.fold(connection.pure(0)) { scheduledVideoDownload =>
          sql"""
          INSERT INTO worker_task(worker_id, scheduled_video_id)
          VALUES(${worker.id}, ${scheduledVideoDownload.videoMetadata.id})
        """.update.run
        }
      }
      .transact(transactor)

  def getById(workerId: String): F[Worker] =
    sql"SELECT id, reserved_at, task_assigned_at FROM worker WHERE id = $workerId"
      .query[(String, Option[DateTime], Option[DateTime])]
      .unique
      .transact(transactor)
      .flatMap {
        case (id, reservedAt, None) => Applicative[F].pure(Worker(id, reservedAt, None))

        case (id, reservedAt, Some(taskAssignedAt)) =>
          sql"SELECT scheduled_video_id FROM worker_task WHERE worker_id = $workerId AND created_at = $taskAssignedAt"
            .query[String]
            .unique
            .transact(transactor)
            .flatMap { scheduledVideoId =>
                schedulingDao.getById(scheduledVideoId).getOrElseF {
                  ApplicativeError[F, Throwable].raiseError(ResourceNotFoundException(s"ScheduleVideoDownload not found. ID = $scheduledVideoId"))
                }
            }
            .map(scheduledVideoDownload => Worker(id, reservedAt, Some(scheduledVideoDownload)))
      }

  override val idleWorker: OptionT[F, Worker] =
    OptionT { sql"SELECT id FROM worker WHERE reserved_at IS NULL LIMIT 1".query[String].option }
      .transact(transactor)
      .semiflatMap(getById)

  def reserveWorker(workerId: String): OptionT[F, Worker] =
    OptionT.liftF(Clock[F].realTime(TimeUnit.MILLISECONDS))
      .flatMap { timestamp =>
        singleUpdate {
          sql"UPDATE worker SET reserved_at = ${new DateTime(timestamp)} WHERE id = $workerId".update.run
        }
          .transact(transactor)
          .productR(OptionT.liftF(getById(workerId)))
      }

  override def assignTask(workerId: String, scheduledVideoId: String): OptionT[F, Worker] =
    OptionT.liftF(Clock[F].realTime(TimeUnit.MILLISECONDS))
        .flatMap { timestamp =>
          singleUpdate {
            sql"""
              INSERT INTO worker_task(worker_id, scheduled_video_id, created_at)
              VALUES ($workerId, $scheduledVideoId, ${new DateTime(timestamp)})
            """.update.run
          }
            .product {
              singleUpdate {
                sql"""
                  UPDATE worker SET task_assigned_at = ${new DateTime(timestamp)} WHERE id = $workerId
                """
                  .update
                  .run
              }
            }
            .transact(transactor)
        }
      .productR(OptionT.liftF(getById(workerId)))

  override def release(workerId: String): OptionT[F, Worker] =
    OptionT.liftF(Clock[F].realTime(TimeUnit.MILLISECONDS))
      .flatMap { timestamp =>
        singleUpdate { sql"UPDATE worker SET reserved_at = NULL, task_assigned_at = NULL WHERE id = $workerId".update.run }
          .productR {
            OptionT.liftF {
              sql"""
                UPDATE worker_task SET completed_at = ${new DateTime(timestamp)}
                WHERE worker_id = $workerId AND completed_at IS NULL
              """
                .update
                .run
            }
          }
          .transact(transactor)
          .productR(OptionT.liftF(getById(workerId)))
      }

}
