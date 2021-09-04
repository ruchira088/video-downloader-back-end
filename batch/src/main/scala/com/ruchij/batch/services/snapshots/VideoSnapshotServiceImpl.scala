package com.ruchij.batch.services.snapshots

import cats.ApplicativeError
import cats.data.OptionT
import cats.effect.{Clock, Sync}
import cats.implicits._
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.services.cli.CliCommandRunner
import com.ruchij.core.services.hashing.HashingService
import com.ruchij.core.services.repository.FileRepositoryService
import com.ruchij.core.types.JodaClock

import scala.concurrent.duration.FiniteDuration

class VideoSnapshotServiceImpl[F[_]: Sync: Clock](
  cliCommandRunner: CliCommandRunner[F],
  fileRepositoryService: FileRepositoryService[F],
  hashingService: HashingService[F]
) extends VideoSnapshotService[F] {

  override def takeSnapshot(
    videoFileKey: String,
    videoTimestamp: FiniteDuration,
    snapshotDestination: String
  ): F[FileResource] =
    cliCommandRunner
      .run(s"ffmpeg -ss ${videoTimestamp.toSeconds} -i $videoFileKey -frames:v 1 -q:v 2 $snapshotDestination -y -v error")
      .compile
      .drain
      .productR {
        OptionT(fileRepositoryService.size(snapshotDestination))
          .product(OptionT(fileRepositoryService.fileType(snapshotDestination)))
          .getOrElseF {
            ApplicativeError[F, Throwable]
              .raiseError(ResourceNotFoundException(s"Unable to find file resource at $snapshotDestination"))
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
