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
import com.ruchij.core.types.JodaClock

import scala.concurrent.duration.FiniteDuration

class VideoSnapshotServiceImpl[F[_]: Async: JodaClock](
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
                timestamp <- JodaClock[F].timestamp
                videoFileKeyHash <- hashingService.hash(videoFileKey)
                id = s"$videoFileKeyHash-snapshot-${videoTimestamp.toMillis}"
              } yield FileResource(id, timestamp, snapshotDestination, fileType, fileSize)
          }
      }

}
