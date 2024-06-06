package com.ruchij.api.test.mixins.io

import cats.effect.IO
import cats.effect.kernel.Async
import com.ruchij.api.test.mixins.MockedRoutes
import com.ruchij.core.types.JodaClock
import fs2.compression.Compression
import org.scalatest.TestSuite

trait MockedRoutesIO extends MockedRoutes[IO] { self: TestSuite =>
  override val async: Async[IO] = Async[IO]

  override val jodaClock: JodaClock[IO] = JodaClock[IO]

  override val compression: Compression[IO] = Compression.forIO
}
