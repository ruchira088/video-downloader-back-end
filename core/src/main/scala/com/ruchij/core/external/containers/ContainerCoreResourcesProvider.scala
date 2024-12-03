package com.ruchij.core.external.containers

import cats.effect.{Resource, Sync}
import cats.implicits._
import com.ruchij.core.config.{KafkaConfiguration, SpaSiteRendererConfiguration}
import com.ruchij.core.external.CoreResourcesProvider
import com.ruchij.migration.config.DatabaseConfiguration
import org.testcontainers.containers.{GenericContainer, Network}
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName

class ContainerCoreResourcesProvider[F[_]: Sync]
    extends CoreResourcesProvider[F] {
  override val kafkaConfiguration: Resource[F, KafkaConfiguration] =
    for {
      network <- Resource.eval(Sync[F].delay(Network.newNetwork()))
      kafkaNetworkAlias = "kafka"

      kafkaContainer <- ContainerCoreResourcesProvider.start[F, ConfluentKafkaContainer] {
        new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.2"))
          .withNetwork(network)
          .withNetworkAliases(kafkaNetworkAlias)
      }

      schemaRegistryUrl <- SchemaRegistryContainer.create(s"$kafkaNetworkAlias:9093", network)

      kafkaBootstrapServers <- Resource.eval(Sync[F].delay(kafkaContainer.getBootstrapServers))

    } yield KafkaConfiguration("local-dev", kafkaBootstrapServers, schemaRegistryUrl)

  override val databaseConfiguration: Resource[F, DatabaseConfiguration] =
    PostgresContainer.create[F]

  override val spaSiteRendererConfiguration: Resource[F, SpaSiteRendererConfiguration] =
    SpaRendererContainer.create[F]
}

object ContainerCoreResourcesProvider {
  def start[F[_]: Sync, A <: GenericContainer[A]](testContainer: A): Resource[F, A] =
    Resource.make[F, A](Sync[F].delay(testContainer.start()).as(testContainer)) { container =>
      Sync[F].delay {
        container.stop()
        container.close()
      }
    }
}
