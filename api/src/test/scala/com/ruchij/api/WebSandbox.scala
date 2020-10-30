package com.ruchij.api

import cats.effect.{ExitCode, IO, IOApp}

object WebSandbox extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = IO.pure(ExitCode.Success)

}
