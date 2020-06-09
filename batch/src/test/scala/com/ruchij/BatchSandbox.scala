package com.ruchij

import cats.effect.{Blocker, ExitCode, IO, IOApp}
import com.ruchij.services.repository.FileRepositoryService

object BatchSandbox extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    Blocker[IO].use {
      blocker =>
        val fileRepositoryService = new FileRepositoryService[IO](blocker)

        fileRepositoryService.list("./videos")
          .take(64)
          .evalTap {
            key => IO.delay(println(key))
          }
          .compile
          .drain
          .as(ExitCode.Success)
    }

}
