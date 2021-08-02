package com.ruchij.api.test.mixins.io

import cats.effect.{Concurrent, ContextShift, IO}
import com.ruchij.api.test.mixins.MockedRoutes
import org.scalamock.scalatest.MockFactory
import org.scalatest.OneInstancePerTest

import scala.concurrent.ExecutionContext

trait MockedRoutesIO extends MockedRoutes[IO] with OneInstancePerTest { self: MockFactory =>
  override val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  override val concurrent: Concurrent[IO] = IO.ioConcurrentEffect(contextShift)
}
