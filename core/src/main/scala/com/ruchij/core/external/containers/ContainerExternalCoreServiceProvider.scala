package com.ruchij.core.external.containers

import cats.effect.{Resource, Sync}
import cats.implicits._
import com.ruchij.core.config.{KafkaConfiguration, SpaSiteRendererConfiguration}
import com.ruchij.core.external.ExternalCoreServiceProvider
import com.ruchij.migration.config.DatabaseConfiguration
import org.testcontainers.containers.{GenericContainer, KafkaContainer, Network}
import org.testcontainers.utility.DockerImageName

class ContainerExternalCoreServiceProvider[F[_]: Sync]
    extends ExternalCoreServiceProvider[F] {
  override val kafkaConfiguration: Resource[F, KafkaConfiguration] =
    for {
      network <- Resource.eval(Sync[F].delay(Network.newNetwork()))

      kafkaContainer <- ContainerExternalCoreServiceProvider.start[F, KafkaContainer] {
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"))
          .withNetwork(network)
          .withNetworkAliases("kafka")
      }

      kafkaBootstrapServers <- Resource.eval(Sync[F].delay(kafkaContainer.getBootstrapServers))

      schemaRegistryUrl <- SchemaRegistryContainer.create("kafka", network)
    } yield KafkaConfiguration("local-dev", kafkaBootstrapServers, schemaRegistryUrl)

  override val databaseConfiguration: Resource[F, DatabaseConfiguration] =
    PostgresContainer.create[F]

  override val spaSiteRendererConfiguration: Resource[F, SpaSiteRendererConfiguration] =
    SpaRendererContainer.create[F]
}

object ContainerExternalCoreServiceProvider {
  def start[F[_]: Sync, A <: GenericContainer[A]](testContainer: A): Resource[F, A] =
    Resource.make[F, A](Sync[F].delay(testContainer.start()).as(testContainer)) { container =>
      Sync[F].delay {
        container.stop()
        container.close()
      }
    }
}
