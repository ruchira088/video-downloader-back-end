package com.ruchij

import cats.effect.{Blocker, ExitCode, IO, IOApp}
import cats.implicits._
import com.ruchij.services.hashing.MurmurHash3Service
import org.joda.time.LocalTime

import scala.language.postfixOps

object WebSandbox extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    IO.delay(LocalTime.now())
      .flatMap(time => IO.delay(println(time)))
      .as(ExitCode.Success)
}
