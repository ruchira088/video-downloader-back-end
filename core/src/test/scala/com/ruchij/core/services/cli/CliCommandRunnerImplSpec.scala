package com.ruchij.core.services.cli

import cats.effect.IO
import cats.effect.std.Dispatcher
import com.ruchij.core.exceptions.CliCommandException
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

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

  it should "return a failure if the CLI command throws an error" in runIO {
    Dispatcher.parallel[IO].use { dispatcher =>
      val cliCommandRunner = new CliCommandRunnerImpl[IO](dispatcher)

      cliCommandRunner.run("invalid-program")
        .compile
        .drain
        .error
        .flatMap { exception =>
          IO.delay {
            exception mustBe a [CliCommandException]
            exception.getMessage contains "invalid-program: command not found"
          }
        }

    }
  }

  it should "return a failure if the CLI command returns a non-zero return code" in runIO {
    Dispatcher.parallel[IO].use { dispatcher =>
      val cliCommandRunner = new CliCommandRunnerImpl[IO](dispatcher)

      cliCommandRunner.run("exit 1")
        .compile
        .drain
        .error
        .flatMap { exception =>
          IO.delay {
            exception mustBe a [CliCommandException]
            exception.getMessage mustBe "CLI command exited with 1 code"
          }
        }
    }
  }

}
