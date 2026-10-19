package com.ruchij.core.services.cli

import fs2.Stream

trait CliCommandRunner[F[_]] {
  /** Runs the program at the head of `arguments` with the remaining elements as its arguments. No shell is involved,
    * so the arguments are passed to the program verbatim and are never subject to shell expansion. */
  def run(arguments: List[String]): Stream[F, String]
}
