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

  it should "deliver every line of a large output, in order" in runIO {
    Dispatcher.parallel[IO].use { dispatcher =>
      val cliCommandRunner = new CliCommandRunnerImpl[IO](dispatcher)

      cliCommandRunner.run(List("seq", "1", "20000"))
        .compile
        .toList
        .flatMap { lines => IO.delay(lines mustBe (1 to 20000).map(_.toString).toList) }
    }
  }

  it should "deliver the output written just before the process exits" in runIO {
    Dispatcher.parallel[IO].use { dispatcher =>
      val cliCommandRunner = new CliCommandRunnerImpl[IO](dispatcher)

      cliCommandRunner.run(List("sh", "-c", "sleep 0.3; echo last"))
        .compile
        .toList
        .flatMap { lines => IO.delay(lines mustBe List("last")) }
    }
  }

  it should "always fail a command that writes to stderr, even if it exits with 0" in runIO {
    Dispatcher.parallel[IO].use { dispatcher =>
      val cliCommandRunner = new CliCommandRunnerImpl[IO](dispatcher)

      cliCommandRunner.run(List("sh", "-c", "echo output; echo problem >&2"))
        .compile
        .drain
        .error
        .flatMap { exception =>
          IO.delay {
            exception mustBe a [CliCommandException]
            exception.getMessage mustBe "problem"
          }
        }
    }
  }

  it should "stop a running process without waiting for it once enough output has been read" in runIO {
    Dispatcher.parallel[IO].use { dispatcher =>
      val cliCommandRunner = new CliCommandRunnerImpl[IO](dispatcher)

      cliCommandRunner.run(List("sh", "-c", "echo first; sleep 60"))
        .take(1)
        .compile
        .toList
        .timed
        .flatMap { case (duration, lines) =>
          IO.delay {
            lines mustBe List("first")
            duration.toSeconds must be < 30L
          }
        }
    }
  }
}
