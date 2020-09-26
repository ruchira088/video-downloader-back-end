package com.ruchij.batch

import cats.effect.{ExitCode, IO, IOApp}

object BatchSandbox extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = IO.pure(ExitCode.Success)

}
