package com.ruchij.core.services.cli

import cats.Applicative
import cats.effect.{ConcurrentEffect, Effect, Sync, Timer}
import cats.implicits._
import fs2.Stream
import fs2.concurrent.Topic

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.sys.process._

class CliCommandRunnerImpl[F[_]: ConcurrentEffect: Timer] extends CliCommandRunner[F] {

  override def run(command: String): Stream[F, String] =
    for {
      topic <- Stream.eval(Topic[F, Option[String]](None))

      process <-
        Stream.eval {
          Sync[F].delay {
            command.run {
              new ProcessLogger {
                override def out(output: => String): Unit =
                  Effect[F].toIO(topic.publish1(Some(output))).unsafeRunSync()

                override def err(error: => String): Unit = {}

                override def buffer[T](f: => T): T = f
              }
            }
          }
        }

      line <-
        topic.subscribe(Int.MaxValue)
          .collect { case Some(line) => line }
          .interruptWhen {
            Stream.eval(Sync[F].delay(process.isAlive()))
              .repeat
              .metered(1 second)
              .map(isAlive => !isAlive)
          }
          .onFinalize {
            Sync[F].delay(process.isAlive())
              .flatMap {
                isAlive =>
                  if (isAlive) Sync[F].delay(process.destroy()).productR(Sync[F].delay(println("Process was killed")))
                  else Applicative[F].unit
              }
          }
    }
    yield line

}
