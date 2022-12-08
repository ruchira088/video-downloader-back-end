package com.ruchij.api.external.containers

import cats.effect.kernel.{Resource, Sync}
import cats.implicits._
import com.ruchij.api.config.FallbackApiConfiguration
import com.ruchij.api.external.containers.FallbackApiContainer.{AdminBearerToken, Port}
import com.ruchij.core.external.containers.ContainerExternalCoreServiceProvider
import com.ruchij.core.types.FunctionKTypes.{FunctionK2TypeOps, eitherLeftFunctor, eitherToF}
import org.http4s.Uri
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.SeqHasAsJava
import scala.language.postfixOps

class FallbackApiContainer
    extends GenericContainer[FallbackApiContainer]("ghcr.io/ruchira088/video-downloader-fallback-api:main") {
  setExposedPorts(List[Integer](Port).asJava)
  addEnv("ADMIN_BEARER_TOKEN", AdminBearerToken)
  setWaitStrategy(new LogMessageWaitStrategy().withRegEx(".*com.ruchij.ApiApp - Started ApiApp.*\\n"))
}

object FallbackApiContainer {
  private val Port = 8080
  private val AdminBearerToken = "my-secret-token"

  def create[F[_]: Sync]: Resource[F, FallbackApiConfiguration] =
    Resource.eval(Sync[F].delay(new FallbackApiContainer()))
      .flatMap(fallbackApiContainer => ContainerExternalCoreServiceProvider.start(fallbackApiContainer))
      .evalMap { fallbackApiContainer =>
        for {
          host <- Sync[F].blocking(fallbackApiContainer.getHost())
          port <- Sync[F].blocking(fallbackApiContainer.getMappedPort(Port))
          uri <- Uri.fromString(s"http://$host:$port").toType[F, Throwable]
        } yield FallbackApiConfiguration(uri, AdminBearerToken, 5 minutes)
      }

}
