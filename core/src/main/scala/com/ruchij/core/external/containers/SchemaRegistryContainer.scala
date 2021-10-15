package com.ruchij.core.external.containers

import cats.effect.Sync
import cats.implicits._
import com.ruchij.core.types.FunctionKTypes._
import org.http4s.Uri
import org.testcontainers.containers.{GenericContainer, Network}

class SchemaRegistryContainer(network: Network, kafkaBootstrapHost: String)
    extends GenericContainer[SchemaRegistryContainer]("confluentinc/cp-schema-registry:6.0.4") {
  private val Port = 8081

  withExposedPorts(Port)
  withNetwork(network)
  withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", s"$kafkaBootstrapHost:9092")
  withEnv("SCHEMA_REGISTRY_HOST_NAME", "localhost")

  def schemaRegistryUrl[F[_]: Sync]: F[Uri] =
    Sync[F].delay(s"http://$getHost:${getMappedPort(Port)}")
      .flatMap { uriString =>
        Uri.fromString(uriString).toType[F, Throwable]
      }

}
