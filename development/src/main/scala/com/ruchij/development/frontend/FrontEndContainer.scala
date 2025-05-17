package com.ruchij.development.frontend

import cats.effect.kernel.{Resource, Sync}
import cats.implicits._
import com.ruchij.core.external.containers.ContainerCoreResourcesProvider
import com.ruchij.core.types.FunctionKTypes.{FunctionK2TypeOps, _}
import org.http4s.Uri
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

import scala.jdk.CollectionConverters.SeqHasAsJava

class FrontEndContainer
    extends GenericContainer[FrontEndContainer]("ghcr.io/ruchira088/video-downloader-front-end:dev") {
  setWaitStrategy(Wait.forHttp("/"))
  setExposedPorts(List(FrontEndContainer.Port: Integer).asJava)
}

object FrontEndContainer {
  private val Port = 80

  def create[F[_]: Sync](apiUrl: Uri): Resource[F, Uri] =
    Resource
      .eval(Sync[F].delay(new FrontEndContainer()))
      .flatMap(frontEndContainer => ContainerCoreResourcesProvider.start(frontEndContainer))
      .evalMap { frontEndContainer =>
        for {
          host <- Sync[F].delay(frontEndContainer.getHost)
          port <- Sync[F].delay(frontEndContainer.getMappedPort(Port))
          uri <- Uri.fromString(s"http://$host:$port?API_URL=${apiUrl.renderString}").toType[F, Throwable]
        } yield uri
      }
}
