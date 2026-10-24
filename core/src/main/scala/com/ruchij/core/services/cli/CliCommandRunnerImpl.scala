package com.ruchij.core.services.cli

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Async, Deferred, Sync}
import cats.implicits._
import com.ruchij.core.exceptions.CliCommandException
import com.ruchij.core.logging.Logger
import fs2.Stream

import java.io.IOException
import scala.sys.process._

class CliCommandRunnerImpl[F[_]: Async](dispatcher: Dispatcher[F]) extends CliCommandRunner[F] {

  private val logger = Logger[CliCommandRunnerImpl[F]]

  /**
    * Each line is put in the queue before the thread reading the process's output moves on, and `exitValue` waits
    * for those threads to finish. So by the time the exit code is known, every line is in the queue, ahead of the
    * marker that ends the stream, and none can be lost however late the threads run.
    */
  override def run(arguments: List[String]): Stream[F, String] = {
    val command = arguments.mkString(" ")

    for {
      _ <- Stream.eval(logger.info[F](s"Executing CLI command: $command"))
      queue <- Stream.eval(Queue.unbounded[F, Option[Either[Throwable, String]]])
      deferred <- Stream.eval(Deferred[F, Throwable])

      process <- Stream.bracket {
        Sync[F].blocking {
          Process(arguments).run {
            new ProcessLogger {
              override def out(output: => String): Unit =
                dispatcher.unsafeRunSync(queue.offer(Some(Right(output))))

              override def err(error: => String): Unit =
                dispatcher.unsafeRunSync {
                  logger
                    .error[F](s"Exception thrown by CLI command: $command", CliCommandException(error))
                    .productR(deferred.complete(CliCommandException(error)).void)
                }

              override def buffer[T](f: => T): T = f
            }
          }
        }.adaptError {
          // Without a shell in between, a missing program surfaces as an IOException rather than a non-zero exit code
          case ioException: IOException => CliCommandException(ioException.getMessage)
        }
      } { process =>
        Sync[F]
          .delay(process.isAlive())
          .flatMap { isAlive =>
            if (isAlive)
              Sync[F]
                .delay(process.destroy())
                .productR(logger.info[F](s"Process was killed: $command"))
            else logger.debug[F](s"Completed command: $command")
          }
      }

      // Interruptible, so that a consumer that stops early isn't held up until the process exits
      outcome = Sync[F].interruptible(process.exitValue()).flatMap { exitCode =>
        deferred.tryGet.map {
          case Some(throwable) => Some(Left(throwable))
          case None if exitCode == 0 => None
          case None => Some(Left(CliCommandException(s"CLI command exited with $exitCode code")))
        }
      }

      line <- Stream
        .fromQueueNoneTerminated(queue)
        .rethrow
        .evalTap(logger.debug[F])
        .concurrently(Stream.eval(outcome.flatMap(queue.offer)))
    } yield line
  }

}
