package com.ruchij.core.external.containers

import cats.effect.{Resource, Sync}
import cats.implicits._
import com.ruchij.core.config.{KafkaConfiguration, SpaSiteRendererConfiguration}
import com.ruchij.core.external.CoreResourcesProvider
import com.ruchij.migration.config.DatabaseConfiguration
import org.apache.kafka.clients.admin.{Admin, AdminClientConfig}
import org.testcontainers.containers.{GenericContainer, Network}
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName

import java.util.Properties
import java.util.concurrent.TimeUnit
import scala.util.Using

class ContainerCoreResourcesProvider[F[_]: Sync]
    extends CoreResourcesProvider[F] {
  override val kafkaConfiguration: Resource[F, KafkaConfiguration] =
    ContainerCoreResourcesProvider.readyKafka[F](ContainerCoreResourcesProvider.ContainerAttempts)

  override val databaseConfiguration: Resource[F, DatabaseConfiguration] =
    PostgresContainer.create[F]

  override val spaSiteRendererConfiguration: Resource[F, SpaSiteRendererConfiguration] =
    SpaRendererContainer.create[F]
}

object ContainerCoreResourcesProvider {
  // A started broker's mapped port can stop answering on a loaded machine, so the broker is only handed out once an
  // admin client can reach it
  private val ReadinessAttempts = 5

  // Once a container's mapped port stops answering, retrying it doesn't help, so a fresh container is started instead
  private val ContainerAttempts = 3

  private def readyKafka[F[_]: Sync](attempts: Int): Resource[F, KafkaConfiguration] =
    kafka[F].attempt.flatMap {
      case Right(kafkaConfiguration) => Resource.pure(kafkaConfiguration)
      case Left(_) if attempts > 1 => readyKafka(attempts - 1)
      case Left(throwable) => Resource.raiseError[F, KafkaConfiguration, Throwable](throwable)
    }

  private def kafka[F[_]: Sync]: Resource[F, KafkaConfiguration] =
    for {
      network <- Resource.eval(Sync[F].delay(Network.newNetwork()))
      kafkaNetworkAlias = "kafka"

      kafkaContainer <- ContainerCoreResourcesProvider.start[F, ConfluentKafkaContainer] {
        new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:8.3.1"))
          .withNetwork(network)
          .withNetworkAliases(kafkaNetworkAlias)
      }

      schemaRegistryUrl <- SchemaRegistryContainer.create[F](s"$kafkaNetworkAlias:9093", network)

      kafkaBootstrapServers <- Resource.eval(Sync[F].delay(kafkaContainer.getBootstrapServers))

      _ <- Resource.eval(awaitBrokerReady[F](kafkaBootstrapServers, ReadinessAttempts))
    } yield KafkaConfiguration("local-dev", kafkaBootstrapServers, schemaRegistryUrl)

  private def awaitBrokerReady[F[_]: Sync](bootstrapServers: String, attempts: Int): F[Unit] =
    Sync[F]
      .blocking {
        val properties = new Properties()
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000")
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000")

        Using.resource(Admin.create(properties))(_.describeCluster().nodes().get(10, TimeUnit.SECONDS))
      }
      .void
      .handleErrorWith { throwable =>
        if (attempts > 1) Sync[F].blocking(Thread.sleep(1000)) >> awaitBrokerReady(bootstrapServers, attempts - 1)
        else Sync[F].raiseError(throwable)
      }

  def start[F[_]: Sync, A <: GenericContainer[A]](testContainer: A): Resource[F, A] =
    Resource.make[F, A](Sync[F].delay(testContainer.start()).as(testContainer)) { container =>
      Sync[F].delay {
        container.stop()
        container.close()
      }
    }
}
