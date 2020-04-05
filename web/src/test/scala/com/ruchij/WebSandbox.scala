package com.ruchij

import cats.effect.{Blocker, ExitCode, IO, IOApp}
import cats.implicits._
import com.ruchij.services.hashing.MurmurHash3Service

import scala.language.postfixOps

object WebSandbox extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    Blocker[IO]
      .use { blocker =>
        val hashingService = new MurmurHash3Service[IO](blocker)

        hashingService.hash("hello-world")
      }
      .flatMap(hashed => IO.delay(println(hashed)))
      .as(ExitCode.Success)
}
