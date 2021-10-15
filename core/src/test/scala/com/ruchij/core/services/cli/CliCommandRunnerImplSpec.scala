package com.ruchij.core.services.cli

import cats.effect.IO
import com.ruchij.core.test.IOSupport.runIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class CliCommandRunnerImplSpec extends AnyFlatSpec with Matchers {

  "CliCommandRunnerImpl.run" should "execute the CLI commands" in runIO {
    val cliCommandRunner = new CliCommandRunnerImpl[IO]

    cliCommandRunner.run("""echo "Hello World"""")
      .compile
      .string
      .flatMap { output => IO.delay(output mustBe "Hello World")}
  }

}
