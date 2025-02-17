package com.ruchij.core.external.containers

import cats.effect.Sync
import cats.effect.kernel.Resource
import cats.implicits._
import com.ruchij.core.types.FunctionKTypes._
import org.http4s.Uri
import org.testcontainers.containers.{GenericContainer, Network}

class SchemaRegistryContainer
    extends GenericContainer[SchemaRegistryContainer]("confluentinc/cp-schema-registry:7.8.1")

object SchemaRegistryContainer {
  private val Port = 8081

  def create[F[_]: Sync](kafkaBootstrapServers: String, network: Network): Resource[F, Uri] =
    Resource
      .eval {
        Sync[F].delay {
          new SchemaRegistryContainer()
            .withNetwork(network)
            .withExposedPorts(Port)
            .withEnv("SCHEMA_REGISTRY_HOST_NAME", "localhost")
            .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", kafkaBootstrapServers)
        }
      }
      .flatMap(schemaRegistryContainer => ContainerCoreResourcesProvider.start(schemaRegistryContainer))
      .evalMap { schemaRegistryContainer =>
        for {
          host <- Sync[F].delay(schemaRegistryContainer.getHost)
          port <- Sync[F].delay(schemaRegistryContainer.getMappedPort(Port))
          uri <- Uri.fromString(s"http://$host:$port").toType[F, Throwable]
        } yield uri
      }
}
