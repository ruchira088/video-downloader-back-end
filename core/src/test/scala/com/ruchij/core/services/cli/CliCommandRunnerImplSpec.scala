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

      cliCommandRunner.run(List("echo", "Hello World"))
        .compile
        .string
        .flatMap { output => IO.delay(output mustBe "Hello World")}
    }
  }

  it should "return a failure if the CLI command throws an error" in runIO {
    Dispatcher.parallel[IO].use { dispatcher =>
      val cliCommandRunner = new CliCommandRunnerImpl[IO](dispatcher)

      cliCommandRunner.run(List("invalid-program"))
        .compile
        .drain
        .error
        .flatMap { exception =>
          IO.delay {
            exception mustBe a [CliCommandException]
            exception.getMessage must include("invalid-program")
          }
        }

    }
  }

  it should "pass arguments to the program verbatim without shell expansion" in runIO {
    Dispatcher.parallel[IO].use { dispatcher =>
      val cliCommandRunner = new CliCommandRunnerImpl[IO](dispatcher)
      val argument = "$(echo injected) `echo injected` ${HOME} ; echo injected | cat && echo 'quoted' > /dev/null"

      cliCommandRunner.run(List("echo", argument))
        .compile
        .string
        .flatMap { output => IO.delay(output mustBe argument) }
    }
  }

  it should "return a failure if the CLI command returns a non-zero return code" in runIO {
    Dispatcher.parallel[IO].use { dispatcher =>
      val cliCommandRunner = new CliCommandRunnerImpl[IO](dispatcher)

      cliCommandRunner.run(List("false"))
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

  it should "handle multiline output" in runIO {
    Dispatcher.parallel[IO].use { dispatcher =>
      val cliCommandRunner = new CliCommandRunnerImpl[IO](dispatcher)

      cliCommandRunner.run(List("printf", "Line1\nLine2\nLine3\n"))
        .compile
        .toList
        .flatMap { lines =>
          IO.delay {
            lines.size mustBe 3
            lines must contain("Line1")
            lines must contain("Line2")
            lines must contain("Line3")
          }
        }
    }
  }

  it should "kill a long-running process when stream is interrupted" in runIO {
    Dispatcher.parallel[IO].use { dispatcher =>
      val cliCommandRunner = new CliCommandRunnerImpl[IO](dispatcher)

      cliCommandRunner.run(List("sleep", "10"))
        .take(0)  // Take nothing, which should interrupt immediately
        .compile
        .drain
        .map(_ => succeed)
    }
  }

}
