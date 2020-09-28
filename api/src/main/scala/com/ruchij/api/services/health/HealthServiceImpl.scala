package com.ruchij.api.services.health

import cats.data.OptionT
import cats.effect.{Concurrent, Timer}
import cats.implicits._
import cats.~>
import com.eed3si9n.ruchij.api.BuildInfo
import com.ruchij.api.services.health.models.HealthCheck.HealthCheckKey
import com.ruchij.api.services.health.models.{HealthCheck, HealthStatus, ServiceInformation}
import com.ruchij.core.config.{ApplicationInformation, DownloadConfiguration}
import com.ruchij.core.kv.KeySpacedKeyValueStore
import com.ruchij.core.logging.Logger
import com.ruchij.core.services.repository.FileRepositoryService
import com.ruchij.core.types.JodaClock
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import fs2.Stream
import org.joda.time.DateTime

import scala.concurrent.duration._
import scala.language.postfixOps

class HealthServiceImpl[F[_]: Timer: Concurrent](
  fileRepositoryService: FileRepositoryService[F],
  keySpacedKeyValueStore: KeySpacedKeyValueStore[F, HealthCheckKey, DateTime],
  applicationInformation: ApplicationInformation,
  downloadConfiguration: DownloadConfiguration
)(implicit transaction: ConnectionIO ~> F)
    extends HealthService[F] {

  private val logger = Logger[F, HealthServiceImpl[F]]

  val keyValueStoreCheck: F[HealthStatus] =
    JodaClock[F].timestamp
      .flatMap { timestamp =>
        keySpacedKeyValueStore
          .put(HealthCheckKey(timestamp), timestamp)
          .productR(keySpacedKeyValueStore.get(HealthCheckKey(timestamp)))
          .map {
            _.filter(_.getMillis == timestamp.getMillis)
              .as(HealthStatus.Healthy)
              .getOrElse[HealthStatus](HealthStatus.Unhealthy)
          }
          .productL(keySpacedKeyValueStore.remove(HealthCheckKey(timestamp)))
      }

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
    Concurrent[F]
      .race(serviceHealthCheck, timeout)
      .map(_.merge)
      .recoverWith {
        case throwable =>
          logger.errorF("Health check error", throwable).as(HealthStatus.Unhealthy)
      }

  val timeout: F[HealthStatus.Unhealthy.type] =
    Timer[F].sleep(5 seconds).as(HealthStatus.Unhealthy)

  override val serviceInformation: F[ServiceInformation] =
    ServiceInformation.create[F](applicationInformation)

  override val healthCheck: F[HealthCheck] =
    for {
      databaseStatusFiber <- Concurrent[F].start(check(databaseCheck))
      fileRepositoryStatusFiber <- Concurrent[F].start(check(fileRepositoryCheck))
      keyValueStoreStatusFiber <- Concurrent[F].start(check(keyValueStoreCheck))

      databaseStatus <- databaseStatusFiber.join
      fileRepositoryStatus <- fileRepositoryStatusFiber.join
      keyValueStoreStatus <- keyValueStoreStatusFiber.join
    } yield HealthCheck(databaseStatus, fileRepositoryStatus, keyValueStoreStatus)

}
