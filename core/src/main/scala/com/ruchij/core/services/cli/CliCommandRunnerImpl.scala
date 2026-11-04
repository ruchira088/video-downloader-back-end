package com.ruchij.core.services.cli

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Async, Deferred, Sync}
import cats.implicits._
import com.ruchij.core.exceptions.CliCommandException
import com.ruchij.core.logging.Logger
import fs2.Stream

import java.io.{BufferedReader, IOException, InputStream, InputStreamReader}
import scala.jdk.CollectionConverters._

class CliCommandRunnerImpl[F[_]: Async](dispatcher: Dispatcher[F]) extends CliCommandRunner[F] {

  private val logger = Logger[CliCommandRunnerImpl[F]]

  /**
    * Each line is put in the queue before the thread reading the process's output moves on, and the exit code is only
    * read once those threads have finished. So by the time the exit code is known, every line is in the queue, ahead of the
    * marker that ends the stream, and none can be lost however late the threads run.
    */
  override def run(arguments: List[String]): Stream[F, String] = {
    val command = arguments.mkString(" ")

    for {
      _ <- Stream.eval(logger.info[F](s"Executing CLI command: $command"))
      queue <- Stream.eval(Queue.unbounded[F, Option[Either[Throwable, String]]])
      deferred <- Stream.eval(Deferred[F, Throwable])

      running <- Stream.bracket {
        Sync[F].blocking {
          val process = new ProcessBuilder(arguments.asJava).start()
          process.getOutputStream.close()

          val outputReader =
            readLines(process.getInputStream) { output =>
              dispatcher.unsafeRunSync(queue.offer(Some(Right(output))))
            }

          val errorReader =
            readLines(process.getErrorStream) { error =>
              dispatcher.unsafeRunSync {
                logger
                  .error[F](s"Exception thrown by CLI command: $command", CliCommandException(error))
                  .productR(deferred.complete(CliCommandException(error)).void)
              }
            }

          (process, List(outputReader, errorReader))
        }.adaptError {
          // Without a shell in between, a missing program surfaces as an IOException rather than a non-zero exit code
          case ioException: IOException => CliCommandException(ioException.getMessage)
        }
      } { case (process, _) =>
        Sync[F]
          .delay(process.isAlive())
          .flatMap { isAlive =>
            if (isAlive)
              Sync[F]
                .delay {
                  // Children are found first, they can't be traced back to the process once it is gone. Left running,
                  // yt-dlp's ffmpeg would keep writing to a temporary file that a retry of the download also writes to.
                  val descendants = process.descendants().iterator().asScala.toList
                  process.destroy()
                  descendants.foreach(_.destroy())
                }
                .productR(logger.info[F](s"Process was killed: $command"))
            else logger.debug[F](s"Completed command: $command")
          }
      }

      (process, readers) = running

      // Interruptible, so that a consumer that stops early isn't held up until the process exits
      outcome = Sync[F].interruptible {
        val exitCode = process.waitFor()
        readers.foreach(_.join())
        exitCode
      }.flatMap { exitCode =>
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

  private def readLines(input: InputStream)(onLine: String => Unit): Thread = {
    val thread = new Thread(() => {
      val reader = new BufferedReader(new InputStreamReader(input))

      // Destroying the process closes its streams, which ends the reading with an IOException
      try Iterator.continually(reader.readLine()).takeWhile(_ != null).foreach(onLine)
      catch { case _: IOException => () }
      finally reader.close()
    })

    thread.setDaemon(true)
    thread.start()
    thread
  }
}
