package com.ruchij.core.test

import cats.effect.IO

trait IOSupport {
  def run[A](value: IO[A]): A = value.unsafeRunSync()
}
