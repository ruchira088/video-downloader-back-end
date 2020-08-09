package com.ruchij.services.health

import java.util.concurrent.TimeUnit

import cats.data.OptionT
import cats.effect.{Concurrent, Timer}
import cats.implicits._
import cats.~>
import com.eed3si9n.ruchij.BuildInfo
import com.ruchij.config.{ApplicationInformation, DownloadConfiguration}
import com.ruchij.logging.Logger
import com.ruchij.services.health.models.{HealthCheck, HealthStatus, ServiceInformation}
import com.ruchij.services.repository.FileRepositoryService
import com.ruchij.types.JodaClock
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import fs2.Stream

import scala.concurrent.duration.FiniteDuration

class HealthServiceImpl[F[_]: Timer: Concurrent](
  applicationInformation: ApplicationInformation,
  fileRepositoryService: FileRepositoryService[F],
  downloadConfiguration: DownloadConfiguration
)(implicit transaction: ConnectionIO ~> F)
    extends HealthService[F] {

  private val logger = Logger[F, HealthServiceImpl[F]]

  val databaseCheck: F[HealthStatus] =
    transaction {
      sql"SELECT 1".query[Int].unique.map {
        case 1 => HealthStatus.Healthy
        case _ => HealthStatus.Unhealthy
      }
    }

  val fileRepositoryCheck: F[HealthStatus] =
    for {
      timestamp <- JodaClock[F].timestamp.map(_.toString("HH-mm-ss-SSS"))

      videoFileKey = s"${downloadConfiguration.videoFolder}/video-health-check-$timestamp.txt"
      imageFileKey = s"${downloadConfiguration.imageFolder}/image-health-check-$timestamp.txt"

      videoFileFiber <- Concurrent[F].start(fileHealthCheck(videoFileKey))
      imageFileFiber <- Concurrent[F].start(fileHealthCheck(imageFileKey))

      videoResult <- videoFileFiber.join
      imageResult <- imageFileFiber.join

    } yield videoResult + imageResult

  def fileHealthCheck(fileKey: String): F[HealthStatus] =
    OptionT
      .liftF {
        fileRepositoryService.write(fileKey, Stream.emits[F, Byte](BuildInfo.toString.getBytes)).compile.drain
      }
      .productR {
        OptionT(fileRepositoryService.read(fileKey, None, None))
          .semiflatMap(_.compile.toList)
      }
      .map(bytes => new String(bytes.toArray))
      .product(OptionT.liftF(fileRepositoryService.delete(fileKey)))
      .map {
        case (data, deleted) =>
          if (deleted && data.equalsIgnoreCase(BuildInfo.toString)) HealthStatus.Healthy else HealthStatus.Unhealthy
      }
      .getOrElse(HealthStatus.Unhealthy)

  def check(serviceHealthCheck: F[HealthStatus]): F[HealthStatus] =
    Concurrent[F].race(serviceHealthCheck, timeout).map(_.merge)
      .recoverWith {
        case throwable =>
          logger.errorF("Health check error", throwable).as(HealthStatus.Unhealthy)
      }


  val timeout: F[HealthStatus.Unhealthy.type] =
    Timer[F].sleep(FiniteDuration(10, TimeUnit.SECONDS)).as(HealthStatus.Unhealthy)

  override val serviceInformation: F[ServiceInformation] =
    ServiceInformation.create[F](applicationInformation)

  override val healthCheck: F[HealthCheck] =
    for {
      database <- check(databaseCheck)
      fileRepository <- check(fileRepositoryCheck)
    } yield HealthCheck(database, fileRepository)

}
