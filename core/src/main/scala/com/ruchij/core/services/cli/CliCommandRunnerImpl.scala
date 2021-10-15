package com.ruchij.core.services.cli

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Async, Sync}
import cats.implicits._
import com.ruchij.core.exceptions.CliCommandException
import com.ruchij.core.logging.Logger
import fs2.Stream

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.sys.process._

class CliCommandRunnerImpl[F[_]: Async] extends CliCommandRunner[F] {

  private val logger = Logger[CliCommandRunnerImpl[F]]

  override def run(command: String): Stream[F, String] =
    for {
      _ <- Stream.eval(logger.info(s"Executing CLI command: $command"))
      queue <- Stream.eval(Queue.unbounded[F, String])
      dispatcher <- Stream.resource(Dispatcher[F])

      process <- Stream.eval {
        Sync[F].blocking {
          command.run {
            new ProcessLogger {
              override def out(output: => String): Unit =
                dispatcher.unsafeRunAndForget(queue.offer(output))

              override def err(error: => String): Unit =
                dispatcher.unsafeRunAndForget {
                  logger.error[F](s"Exception thrown by CLI command: $command", CliCommandException(error))
                }

              override def buffer[T](f: => T): T = f
            }
          }
        }
      }

      line <-
        Stream.eval(queue.take).repeat
        .evalTap(logger.debug[F])
        .interruptWhen {
          Stream
            .eval(Sync[F].delay(process.isAlive()))
            .repeat
            .metered(100 milliseconds)
            .filter(isAlive => !isAlive)
            .productR {
              Stream.eval(queue.size).map(_ == 0).repeat
            }
        }
        .onFinalize {
          Sync[F]
            .delay(process.isAlive())
            .flatMap { isAlive =>
              if (isAlive)
                Sync[F]
                  .delay(process.destroy())
                  .productR(logger.info(s"Process was killed: $command"))
              else logger.debug(s"Completed command: $command")
            }
        }
    } yield line

}
