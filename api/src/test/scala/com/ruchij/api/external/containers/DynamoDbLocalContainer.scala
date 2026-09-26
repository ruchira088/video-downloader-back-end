package com.ruchij.api.external.containers

import cats.effect.{Resource, Sync}
import com.ruchij.core.external.containers.ContainerCoreResourcesProvider
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

class DynamoDbLocalContainer
    extends GenericContainer[DynamoDbLocalContainer](DockerImageName.parse("amazon/dynamodb-local:latest")) {
  withExposedPorts(8000)
}

object DynamoDbLocalContainer {
  def create[F[_]: Sync]: Resource[F, String] =
    ContainerCoreResourcesProvider
      .start(new DynamoDbLocalContainer)
      .evalMap(container => Sync[F].blocking(s"http://${container.getHost}:${container.getMappedPort(8000)}"))
}
