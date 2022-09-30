package com.ruchij.api.external.containers

import cats.effect.kernel.Sync
import cats.implicits._
import com.ruchij.api.config.FallbackApiConfiguration
import com.ruchij.core.types.FunctionKTypes.{FunctionK2TypeOps, eitherLeftFunctor, eitherToF}
import org.http4s.Uri
import org.testcontainers.containers.GenericContainer

import scala.jdk.CollectionConverters.MapHasAsJava

class FallbackApiContainer extends GenericContainer[FallbackApiContainer]("ghcr.io/ruchira088/video-downloader-fallback-api:main") {
  private val Port = 8080
  private val AdminBearerToken = "my-secret-token"

  withExposedPorts(Port)
  withEnv(Map("ADMIN_BEARER_TOKEN" -> AdminBearerToken).asJava)

  def fallbackApiConfiguration[F[_]: Sync]: F[FallbackApiConfiguration] =
    for {
      host <- Sync[F].blocking(getHost())
      port <- Sync[F].blocking(getMappedPort(Port))
      uri <- Uri.fromString(s"http://$host:$port").toType[F, Throwable]
    }
    yield FallbackApiConfiguration(uri, AdminBearerToken)

}
