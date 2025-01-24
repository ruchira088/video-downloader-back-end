package com.ruchij.core.services.cli

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Async, Deferred, Sync}
import cats.implicits._
import cats.{Applicative, ApplicativeError}
import com.ruchij.core.exceptions.CliCommandException
import com.ruchij.core.logging.Logger
import fs2.Stream

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.sys.process._

class CliCommandRunnerImpl[F[_]: Async](dispatcher: Dispatcher[F]) extends CliCommandRunner[F] {

  private val logger = Logger[CliCommandRunnerImpl[F]]

  override def run(command: String): Stream[F, String] =
    for {
      _ <- Stream.eval(logger.info(s"Executing CLI command: $command"))
      queue <- Stream.eval(Queue.unbounded[F, String])
      deferred <- Stream.eval(Deferred[F, Throwable])

      process <- Stream.eval {
        Sync[F].blocking {
          Process(List("bash", "-c", command)).run {
            new ProcessLogger {
              override def out(output: => String): Unit =
                dispatcher.unsafeRunAndForget(queue.offer(output))

              override def err(error: => String): Unit =
                dispatcher.unsafeRunAndForget {
                  logger
                    .error[F](s"Exception thrown by CLI command: $command", CliCommandException(error))
                    .productR(deferred.complete(CliCommandException(error)))
                }

              override def buffer[T](f: => T): T = f
            }
          }
        }
      }

      line <- Stream
        .eval(queue.take)
        .repeat
        .evalTap(logger.debug[F])
        .interruptWhen {
          Stream
            .eval(Sync[F].delay(process.isAlive()))
            .repeat
            .metered(100 milliseconds)
            .filter(isAlive => !isAlive)
            .productR {
              Stream
                .eval(queue.size)
                .flatMap(size => if (size == 0) Stream.emit[F, Boolean](true) else Stream.empty)
                .repeat
                .evalMap(_ => deferred.tryGet)
                .evalMap {
                  case Some(throwable) => ApplicativeError[F, Throwable].raiseError[Int](throwable)
                  case _ => Sync[F].delay(process.exitValue())
                }
                .evalMap {
                  case 0 => Applicative[F].pure[Boolean](true)
                  case exitCode =>
                    ApplicativeError[F, Throwable].raiseError[Boolean] {
                      CliCommandException(s"CLI command exited with $exitCode code")
                    }
                }
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
