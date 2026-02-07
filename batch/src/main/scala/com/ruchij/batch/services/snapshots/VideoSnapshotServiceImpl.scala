package com.ruchij.batch.services.snapshots

import cats.ApplicativeError
import cats.data.OptionT
import cats.effect.Async
import cats.implicits._
import com.ruchij.batch.exceptions.SynchronizationException
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.services.cli.CliCommandRunner
import com.ruchij.core.services.hashing.HashingService
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.types.{Clock, RandomGenerator}

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

class VideoSnapshotServiceImpl[F[_]: Async: Clock: RandomGenerator[*[_], UUID]](
  cliCommandRunner: CliCommandRunner[F],
  repositoryService: RepositoryService[F],
  hashingService: HashingService[F]
) extends VideoSnapshotService[F] {

  override def takeSnapshot(
    videoFileKey: String,
    videoTimestamp: FiniteDuration,
    snapshotDestination: String
  ): F[FileResource] =
    cliCommandRunner
      .run(s"""ffmpeg -ss ${videoTimestamp.toSeconds} -i "$videoFileKey" -frames:v 1 -q:v 2 "$snapshotDestination" -y -v error""")
      .compile
      .drain
      .productR {
        OptionT(repositoryService.size(snapshotDestination))
          .product(OptionT(repositoryService.fileType(snapshotDestination)))
          .getOrElseF {
            ApplicativeError[F, Throwable]
              .raiseError {
                SynchronizationException {
                  s"Snapshot service was unable to take snapshot of $videoFileKey at ${videoTimestamp.toSeconds}s"
                }
              }
          }
          .flatMap {
            case (fileSize, fileType) =>
              for {
                timestamp <- Clock[F].timestamp
                videoFileKeyHash <- hashingService.hash(videoFileKey)
                suffix <- RandomGenerator[F, UUID].generate.map(_.toString.take(5))
                id = s"$videoFileKeyHash-snapshot-${videoTimestamp.toMillis}-$suffix"
              } yield FileResource(id, timestamp, snapshotDestination, fileType, fileSize)
          }
      }

}
