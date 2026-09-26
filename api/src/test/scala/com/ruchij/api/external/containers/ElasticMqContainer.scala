package com.ruchij.api.external.containers

import cats.effect.{Resource, Sync}
import com.ruchij.core.external.containers.ContainerCoreResourcesProvider
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

class ElasticMqContainer
    extends GenericContainer[ElasticMqContainer](DockerImageName.parse("softwaremill/elasticmq-native:latest")) {
  withExposedPorts(9324)
}

object ElasticMqContainer {
  /** Resolves to the SQS-compatible endpoint URL. */
  def create[F[_]: Sync]: Resource[F, String] =
    ContainerCoreResourcesProvider
      .start(new ElasticMqContainer)
      .evalMap(container => Sync[F].blocking(s"http://${container.getHost}:${container.getMappedPort(9324)}"))
}
