package com.ruchij.core.external.containers

import cats.effect.kernel.Sync
import cats.implicits._
import com.ruchij.core.config.SpaSiteRendererConfiguration
import com.ruchij.core.types.FunctionKTypes._
import org.http4s.Uri
import org.testcontainers.containers.GenericContainer

class SpaRendererContainer
    extends GenericContainer[SpaRendererContainer]("ghcr.io/ruchira088/video-downloader-spa-renderer:dev") {
  private val Port = 8000

  withExposedPorts(Port)

  def spaSiteRendererConfiguration[F[_]: Sync]: F[SpaSiteRendererConfiguration] =
    for {
      port <- Sync[F].blocking(getMappedPort(Port))
      host <- Sync[F].blocking(getHost)
      url <- Uri.fromString(s"http://$host:$port").toType[F, Throwable]
    } yield SpaSiteRendererConfiguration(url)
}
