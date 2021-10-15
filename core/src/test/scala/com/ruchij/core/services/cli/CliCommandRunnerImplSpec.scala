package com.ruchij.core.services.cli

import cats.effect.IO
import cats.effect.std.Queue
import com.ruchij.core.test.IOSupport.runIO
import fs2.concurrent.Topic
import org.scalatest.flatspec.AnyFlatSpec
import fs2.Stream

class CliCommandRunnerImplSpec extends AnyFlatSpec {

  "CliCommandRunnerImpl.run" should "run the CLI command" in runIO {
    Topic[IO, Int].flatMap {
      topic =>
        topic.publish(Stream.range[IO, Int](0, 100))
          .concurrently {
            topic.subscribe(Int.MaxValue).evalMap(number => IO.blocking(println(number)))
          }
          .compile
          .drain
    }
  }

  it should "run" in runIO {
    val cliCommandRunner = new CliCommandRunnerImpl[IO]

    cliCommandRunner.run("date")
      .evalMap(line => IO.blocking(line))
      .compile
      .drain
  }

  it should "queue" in runIO {
    Queue.unbounded[IO, Int].flatMap {
      queue =>
        Stream.range[IO, Int](0, 10000).evalMap(queue.offer)
          .concurrently {
            Stream.eval(queue.take).repeat.evalMap(number => IO.blocking(println(number)))
          }
          .onFinalize {
            Stream.eval(queue.tryTake).repeat.takeWhile(_.nonEmpty)
              .collect {
                case Some(number) => number
              }
              .evalMap(number => IO.blocking(println(number)))
              .compile
              .drain
          }
          .compile
          .drain
    }
  }

}
