package com.ruchij.api.services.health

import cats.data.OptionT
import cats.effect.{Clock, Concurrent}
import cats.effect.kernel.Async
import cats.implicits._
import cats.~>
import com.eed3si9n.ruchij.api.BuildInfo
import com.ruchij.api.services.health.models.kv.HealthCheckKey
import com.ruchij.api.services.health.models.messaging.HealthCheckMessage
import com.ruchij.api.services.health.models.{HealthCheck, HealthStatus, ServiceInformation}
import com.ruchij.core.config.{ApplicationInformation, StorageConfiguration}
import com.ruchij.core.kv.KeySpacedKeyValueStore
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.Publisher
import com.ruchij.core.services.repository.FileRepositoryService
import com.ruchij.core.types.JodaClock
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import fs2.Stream
import org.http4s.Request
import org.http4s.Method.GET
import org.http4s.client.Client
import org.joda.time.DateTime

import scala.concurrent.duration._
import scala.language.postfixOps

class HealthServiceImpl[F[_]: Async: JodaClock](
  fileRepositoryService: FileRepositoryService[F],
  keySpacedKeyValueStore: KeySpacedKeyValueStore[F, HealthCheckKey, DateTime],
  healthCheckStream: Stream[F, HealthCheckMessage],
  healthCheckPublisher: Publisher[F, HealthCheckMessage],
  client: Client[F],
  applicationInformation: ApplicationInformation,
  storageConfiguration: StorageConfiguration
)(implicit transaction: ConnectionIO ~> F)
    extends HealthService[F] {

  private val logger = Logger[HealthServiceImpl[F]]

  private val keyValueStoreCheck: F[HealthStatus] =
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

  private val databaseCheck: F[HealthStatus] =
    transaction {
      sql"SELECT 1".query[Int].unique.map {
        case 1 => HealthStatus.Healthy
        case _ => HealthStatus.Unhealthy
      }
    }

  private val pubSubCheck: F[HealthStatus] =
    JodaClock[F].timestamp
      .flatMap { dateTime =>
        healthCheckStream
          .concurrently {
            healthCheckPublisher.publish {
              Stream.emit[F, HealthCheckMessage] { HealthCheckMessage(applicationInformation.instanceId, dateTime) }
                .repeat
                .take(10)
            }
          }
          .filter {
            case HealthCheckMessage(instanceId, messageDateTime) =>
              instanceId == applicationInformation.instanceId && dateTime.isEqual(messageDateTime)
          }
          .take(10)
          .compile
          .drain
          .as(HealthStatus.Healthy)
      }

  private val fileRepositoryCheck: F[HealthStatus] =
    for {
      timestamp <- JodaClock[F].timestamp.map(_.toString("HH-mm-ss-SSS"))

      imageFileKey = s"${storageConfiguration.imageFolder}/image-health-check-$timestamp.txt"

      imageResult <- fileHealthCheck(imageFileKey)
    } yield imageResult

  private val internetConnectivityCheck: F[HealthStatus] =
    client.status(Request[F](GET, HealthService.ConnectivityUrl)).as(HealthStatus.Healthy)

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
          logger.error[F]("Health check error", throwable).as(HealthStatus.Unhealthy)
      }

  val timeout: F[HealthStatus.Unhealthy.type] =
    Clock[F].sleep(10 seconds).as(HealthStatus.Unhealthy)

  override val serviceInformation: F[ServiceInformation] =
    ServiceInformation.create[F](applicationInformation)

  override val healthCheck: F[HealthCheck] =
    for {
      databaseStatusFiber <- Concurrent[F].start(check(databaseCheck))
      fileRepositoryStatusFiber <- Concurrent[F].start(check(fileRepositoryCheck))
      keyValueStoreStatusFiber <- Concurrent[F].start(check(keyValueStoreCheck))
      pubSubStatusFiber <- Concurrent[F].start(check(pubSubCheck))
      internetConnectivityStatusFiber <- Concurrent[F].start(check(internetConnectivityCheck))

      databaseStatus <- databaseStatusFiber.join.flatMap(_.embedNever)
      fileRepositoryStatus <- fileRepositoryStatusFiber.join.flatMap(_.embedNever)
      keyValueStoreStatus <- keyValueStoreStatusFiber.join.flatMap(_.embedNever)
      pubSubStatus <- pubSubStatusFiber.join.flatMap(_.embedNever)
      internetConnectivityStatus <- internetConnectivityStatusFiber.join.flatMap(_.embedNever)
    } yield HealthCheck(databaseStatus, fileRepositoryStatus, keyValueStoreStatus, pubSubStatus, internetConnectivityStatus)
}
