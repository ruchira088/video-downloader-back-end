package com.ruchij.core.services.cli

import cats.effect.IO
import com.ruchij.core.test.IOSupport.runIO
import org.scalatest.flatspec.AnyFlatSpec

class CliCommandRunnerImplSpec extends AnyFlatSpec {

  "CliCommandRunnerImpl.run" should "run the CLI command" in runIO {
    val cliCommandRunner = new CliCommandRunnerImpl[IO]

    cliCommandRunner.run("echo $HOME")
      .evalTap(line => IO.delay(println(line)))
      .compile
      .drain
  }

}
