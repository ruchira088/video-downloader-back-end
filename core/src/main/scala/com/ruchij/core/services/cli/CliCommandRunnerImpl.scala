package com.ruchij.core.services.cli

import cats.effect.std.Dispatcher
import cats.effect.{Async, Sync}
import cats.implicits._
import com.ruchij.core.exceptions.CliCommandException
import com.ruchij.core.logging.Logger
import fs2.Stream
import fs2.concurrent.Topic

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.sys.process._

class CliCommandRunnerImpl[F[_]: Async] extends CliCommandRunner[F] {

  private val logger = Logger[CliCommandRunnerImpl[F]]

  override def run(command: String): Stream[F, String] =
    for {
      _ <- Stream.eval(logger.info(s"Executing CLI command: $command"))
      topic <- Stream.eval(Topic[F, String])
      dispatcher <- Stream.resource(Dispatcher[F])

      process <- Stream.eval {
        Sync[F].blocking {
          command.run {
            new ProcessLogger {
              override def out(output: => String): Unit =
                dispatcher.unsafeRunSync(topic.publish1(output))

              override def err(error: => String): Unit =
                dispatcher.unsafeRunSync {
                  logger.error[F](s"Exception thrown by CLI command: $command", CliCommandException(error))
                }

              override def buffer[T](f: => T): T = f
            }
          }
        }
      }

      line <- topic
        .subscribe(Int.MaxValue)
        .evalTap(logger.debug[F])
        .interruptWhen {
          Stream
            .eval(Sync[F].delay(process.isAlive()))
            .repeat
            .metered(1 second)
            .map(isAlive => !isAlive)
        }
        .onFinalize {
          Sync[F]
            .delay(process.isAlive())
            .flatMap { isAlive =>
              if (isAlive)
                Sync[F]
                  .delay(process.destroy())
                  .productR(logger.info(s"Process was killed: $command"))
              else logger.debug(s"Completed: $command")
            }
        }
    } yield line

}
