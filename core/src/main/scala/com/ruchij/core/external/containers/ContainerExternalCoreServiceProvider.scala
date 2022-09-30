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
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:6.0.4"))
          .withNetwork(network)
          .withNetworkAliases("kafka")
      }

      schemaRegistryContainer <- ContainerExternalCoreServiceProvider.start[F, SchemaRegistryContainer] {
        new SchemaRegistryContainer(network, "kafka")
      }

      kafkaBootstrapServers <- Resource.eval(Sync[F].delay(kafkaContainer.getBootstrapServers))
      schemaRegistry <- Resource.eval(schemaRegistryContainer.schemaRegistryUrl[F])
    } yield KafkaConfiguration(kafkaBootstrapServers, schemaRegistry)

  override val databaseConfiguration: Resource[F, DatabaseConfiguration] =
    for {
      postgresqlContainer <-
        ContainerExternalCoreServiceProvider.start {
          new PostgresContainer()
            .withUsername("admin")
            .withPassword("password")
            .withDatabaseName("video-downloader")
        }

      databaseUrl <- Resource.eval(Sync[F].delay(postgresqlContainer.getJdbcUrl))
    }
    yield DatabaseConfiguration(databaseUrl, postgresqlContainer.getUsername, postgresqlContainer.getPassword)

  override val spaSiteRendererConfiguration: Resource[F, SpaSiteRendererConfiguration] =
    ContainerExternalCoreServiceProvider.start(new SpaRendererContainer()).evalMap(_.spaSiteRendererConfiguration)
}

object ContainerExternalCoreServiceProvider {
  def start[F[_]: Sync, A <: GenericContainer[A]](testContainer: => A): Resource[F, A] =
    Resource.make[F, A] {
      Sync[F]
        .delay(testContainer)
        .flatTap(container => Sync[F].delay(container.start()))
    } { container =>
      Sync[F].delay(container.stop()).productR(Sync[F].delay(container.close()))
    }
}
