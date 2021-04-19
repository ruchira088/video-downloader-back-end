package com.ruchij.core.test

import cats.effect.IO

object IOSupport {
  def runIO[A](block: => IO[A]): A = block.unsafeRunSync()
}
