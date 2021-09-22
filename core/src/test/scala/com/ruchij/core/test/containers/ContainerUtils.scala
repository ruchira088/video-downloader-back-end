package com.ruchij.core.test.containers

import cats.effect.{Resource, Sync}
import cats.implicits._
import org.testcontainers.containers.GenericContainer

object ContainerUtils {

  def start[F[_]: Sync, A <: GenericContainer[A]](testContainer: => A): Resource[F, A] =
    Resource.make[F, A] {
      Sync[F].delay(testContainer)
        .flatTap(container => Sync[F].delay(container.start()))
    } {
      container => Sync[F].delay(container.stop()).productR(Sync[F].delay(container.close()))
    }

}
