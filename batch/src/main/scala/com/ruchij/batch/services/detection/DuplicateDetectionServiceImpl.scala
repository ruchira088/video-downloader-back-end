package com.ruchij.batch.services.detection

import cats.data.OptionT
import cats.implicits._
import cats.{Applicative, Monad, MonadThrow, ~>}
import com.ruchij.core.daos.hash.VideoPerceptualHashDao
import com.ruchij.core.daos.hash.models.VideoPerceptualHash
import com.ruchij.batch.services.enrichment.VideoEnrichmentService
import com.ruchij.batch.services.enrichment.VideoEnrichmentService.SnapshotCount
import com.ruchij.core.daos.duplicate.DuplicateVideoDao
import com.ruchij.core.daos.duplicate.models.DuplicateVideo
import com.ruchij.core.daos.snapshot.SnapshotDao
import com.ruchij.core.exceptions.InvalidConditionException
import com.ruchij.core.services.hashing.PerceptualHashingService
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.types.Clock
import com.ruchij.core.types.FunctionKTypes.{FunctionKTypeOps, optionToOptionT}

import scala.concurrent.duration.FiniteDuration

class DuplicateDetectionServiceImpl[F[_]: MonadThrow: Clock, G[_]: Monad](
  perceptualHashingService: PerceptualHashingService[F],
  repositoryService: RepositoryService[F],
  videoPerceptualHashDao: VideoPerceptualHashDao[G],
  duplicateVideoDao: DuplicateVideoDao[G],
  snapshotDao: SnapshotDao[G]
)(implicit transaction: G ~> F)
    extends DuplicateDetectionService[F] {
  override def detect: F[Map[FiniteDuration, Set[Set[String]]]] =
    transaction(videoPerceptualHashDao.uniqueVideoDurations)
      .flatMap { durations =>
        durations.toList.traverse { duration =>
          detectDuplicates(duration).map(duration -> _)
        }
      }
      .map(_.toMap)

  private def detectDuplicates(duration: FiniteDuration): F[Set[Set[String]]] = {
    val snapshotDurations = VideoEnrichmentService.snapshotTimestamps(duration, SnapshotCount)
    val snapshotTimestamp = snapshotDurations.sorted.toList(snapshotDurations.size / 2)

    transaction {
      for {
        videoIds <- videoPerceptualHashDao.getVideoIdsByDuration(duration)
        videoHashes <- videoPerceptualHashDao.findVideoHashesByDuration(duration)
        videoHashIds = videoHashes.map(_.videoId).toSet
        videoIdsWithoutHashes = videoIds.filterNot(videoHashIds.contains)
      } yield videoIdsWithoutHashes -> videoHashes
    }.flatMap {
        case (videoIds, videoHashes) =>
          videoIds
            .traverse { videoId =>
              perceptualHash(videoId, duration, snapshotTimestamp).value
            }
            .map(_.collect { case Some(value) => value })
            .flatTap { newVideoHashes =>
              transaction {
                newVideoHashes.traverse { videoHash =>
                  videoPerceptualHashDao
                    .getByVideoId(videoHash.videoId)
                    .map(_.find(_.snapshotTimestamp == videoHash.snapshotTimestamp))
                    .flatMap {
                      case None => videoPerceptualHashDao.insert(videoHash)
                      case _ => Applicative[G].pure(0)
                    }
                }
              }
            }
            .map(_ ++ videoHashes)
      }
      .flatMap(calculateDuplicates)
  }

  private def perceptualHash(
    videoId: String,
    videoDuration: FiniteDuration,
    snapshotTimestamp: FiniteDuration
  ): OptionT[F, VideoPerceptualHash] =
    OptionT {
      transaction {
        snapshotDao
          .findByVideo(videoId, None)
          .map(_.find(_.videoTimestamp == snapshotTimestamp))
      }
    }.flatMapF { snapshot =>
        repositoryService
          .read(snapshot.fileResource.path, None, None)
      }
      .semiflatMap { dataStream =>
        perceptualHashingService.hashImage(dataStream)
      }
      .semiflatMap { hash =>
        Clock[F].timestamp.map { timestamp =>
          VideoPerceptualHash(videoId, timestamp, videoDuration, hash, snapshotTimestamp)
        }
      }

  private def calculateDuplicates(videoHashes: Seq[VideoPerceptualHash]): F[Set[Set[String]]] =
    videoHashes.headOption.fold(Set.empty[Set[String]].pure[F]) { videoHash =>
      calculateDuplicates(videoHash, videoHashes.tail, Set.empty[Set[String]])
    }

  private def calculateDuplicates(
    videoHash: VideoPerceptualHash,
    videoHashes: Seq[VideoPerceptualHash],
    duplicates: Set[Set[String]]
  ): F[Set[Set[String]]] =
    videoHashes
      .traverse { otherVideoHash =>
        perceptualHashingService
          .compareHashes(videoHash.snapshotPerceptualHash, otherVideoHash.snapshotPerceptualHash)
          .map { distance =>
            (otherVideoHash, distance <= DuplicateDetectionService.DifferenceThreshold)
          }
      }
      .flatMap { hashComparisons =>
        {
          val similar: Set[String] =
            hashComparisons.collect { case (otherVideoHash, true) => otherVideoHash.videoId }.toSet +
              videoHash.videoId

          val updatedVideoHashes = videoHashes.filterNot(hash => similar.contains(hash.videoId))

          val existingSimilaritySet: Option[Set[String]] = duplicates.find { similaritySet =>
            similaritySet.intersect(similar).nonEmpty
          }

          val updatedDuplicates: Set[Set[String]] = existingSimilaritySet match {
            case None => if (similar.size > 1) duplicates + similar else duplicates
            case Some(existingSet) => duplicates - existingSet + (existingSet ++ similar)
          }

          updatedVideoHashes.headOption
            .fold(updatedDuplicates.pure[F]) { head =>
              calculateDuplicates(head, updatedVideoHashes.tail, updatedDuplicates)
            }
        }
      }

  override def run: F[Unit] =
    detect.map(_.values.flatten).flatMap {
      _.toList
        .traverse(handleDuplicateVideoSet)
        .void
    }

  private def handleDuplicateVideoSet(duplicateVideoSet: Set[String]): F[Unit] =
    for {
      groupId <- duplicateVideoSet.toSeq.sorted.headOption
        .toType[F, Throwable](InvalidConditionException("duplicate video set cannot be empty"))
      timestamp <- Clock[F].timestamp
      duplicateVideos = duplicateVideoSet.map(
        videoId => DuplicateVideo(videoId = videoId, duplicateGroupId = groupId, createdAt = timestamp)
      )
      _ <- transaction {
        duplicateVideoDao
          .findByDuplicateGroupId(groupId)
          .flatMap { existingVideosForGroup =>
            val existingVideoIdsForGroup = existingVideosForGroup.map(_.videoId).toSet
            val newDuplicateVideosToAdd =
              duplicateVideos.filter(duplicateVideo => !existingVideoIdsForGroup.contains(duplicateVideo.videoId))

            newDuplicateVideosToAdd.toList.traverse(duplicateVideoDao.insert)
          }
      }
    } yield ()
}
