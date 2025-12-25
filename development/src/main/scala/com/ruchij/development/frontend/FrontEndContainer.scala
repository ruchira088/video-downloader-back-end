package com.ruchij.development.frontend

import cats.effect.kernel.{Resource, Sync}
import cats.implicits._
import com.ruchij.core.external.containers.ContainerCoreResourcesProvider
import com.ruchij.core.types.FunctionKTypes._
import org.http4s.Uri
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

import scala.jdk.CollectionConverters.SeqHasAsJava

class FrontEndContainer(apiUrl: Uri)
    extends GenericContainer[FrontEndContainer]("ghcr.io/ruchira088/video-downloader-front-end-dev:main") {
  setExposedPorts(List(FrontEndContainer.Port: Integer).asJava)
  setWaitStrategy(Wait.forHttp("/").forPort(FrontEndContainer.Port))
  addEnv("VITE_API_URL", apiUrl.renderString)
}

object FrontEndContainer {
  private val Port = 5173

  def create[F[_]: Sync](apiUrl: Uri): Resource[F, Uri] =
    Resource
      .eval(Sync[F].delay(new FrontEndContainer(apiUrl)))
      .flatMap(frontEndContainer => ContainerCoreResourcesProvider.start(frontEndContainer))
      .evalMap { frontEndContainer =>
        for {
          host <- Sync[F].delay(frontEndContainer.getHost)
          port <- Sync[F].delay(frontEndContainer.getMappedPort(Port))
          uri <- Uri.fromString(s"http://$host:$port").toType[F, Throwable]
        } yield uri
      }
}
