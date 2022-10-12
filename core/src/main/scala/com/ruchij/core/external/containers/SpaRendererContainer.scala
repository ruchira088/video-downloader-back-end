package com.ruchij.core.external.containers

import cats.effect.kernel.{Resource, Sync}
import cats.implicits._
import com.ruchij.core.config.SpaSiteRendererConfiguration
import com.ruchij.core.types.FunctionKTypes._
import org.http4s.Uri
import org.testcontainers.containers.GenericContainer

class SpaRendererContainer
    extends GenericContainer[SpaRendererContainer]("ghcr.io/ruchira088/video-downloader-spa-renderer:dev")

object SpaRendererContainer {
  private val Port = 8000

  def create[F[_]: Sync]: Resource[F, SpaSiteRendererConfiguration] =
    Resource.eval(Sync[F].delay(new SpaRendererContainer().withExposedPorts(Port)))
      .flatMap(spaRendererContainer => ContainerExternalCoreServiceProvider.start(spaRendererContainer))
      .evalMap { spaRendererContainer =>
        for {
          port <- Sync[F].blocking(spaRendererContainer.getMappedPort(Port))
          host <- Sync[F].blocking(spaRendererContainer.getHost)
          url <- Uri.fromString(s"http://$host:$port").toType[F, Throwable]
        } yield SpaSiteRendererConfiguration(url)
      }
}