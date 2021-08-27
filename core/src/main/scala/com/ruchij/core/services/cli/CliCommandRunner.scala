package com.ruchij.core.services.cli

import fs2.Stream

trait CliCommandRunner[F[_]] {
  def run(command: String): Stream[F, String]
}
