package com.ruchij.core.services.cli

import cats.effect.IO
import cats.effect.std.Dispatcher
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.io.IOException

class CliCommandRunnerImplSpec extends AnyFlatSpec with Matchers {

  "CliCommandRunnerImpl.run" should "execute the CLI commands" in runIO {
    Dispatcher.parallel[IO].use { dispatcher =>
      val cliCommandRunner = new CliCommandRunnerImpl[IO](dispatcher)

      cliCommandRunner.run("""echo "Hello World"""")
        .compile
        .string
        .flatMap { output => IO.delay(output mustBe "Hello World")}
    }
  }

  it should "return a failure if the CLI command exited unexpectedly" in runIO {
    Dispatcher.parallel[IO].use { dispatcher =>
      val cliCommandRunner = new CliCommandRunnerImpl[IO](dispatcher)

      cliCommandRunner.run("invalid-program")
        .compile
        .drain
        .error
        .flatMap { exception =>
          IO.delay {
            exception mustBe a [IOException]
            exception.getMessage mustBe """Cannot run program "invalid-program": error=2, No such file or directory"""
          }
        }

    }
  }

}
