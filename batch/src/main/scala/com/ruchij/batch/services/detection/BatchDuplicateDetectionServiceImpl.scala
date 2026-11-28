package com.ruchij.batch.services.detection

import cats.data.{NonEmptyList, OptionT}
import cats.effect.kernel.Sync
import cats.implicits.*
import cats.{Applicative, Monad, ~>}
import com.ruchij.batch.services.enrichment.VideoEnrichmentService
import com.ruchij.batch.services.enrichment.VideoEnrichmentService.SnapshotCount
import com.ruchij.batch.services.video.BatchVideoService
import com.ruchij.core.daos.duplicate.DuplicateVideoDao
import com.ruchij.core.daos.duplicate.models.DuplicateVideo
import com.ruchij.core.daos.hash.VideoPerceptualHashDao
import com.ruchij.core.daos.hash.models.VideoPerceptualHash
import com.ruchij.core.daos.permission.VideoPermissionDao
import com.ruchij.core.daos.snapshot.SnapshotDao
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.exceptions.InvalidConditionException
import com.ruchij.core.logging.Logger
import com.ruchij.core.services.hashing.PerceptualHashingService
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.types.Clock
import com.ruchij.core.types.FunctionKTypes.{FunctionKTypeOps, optionToOptionT}

import scala.concurrent.duration.FiniteDuration

class BatchDuplicateDetectionServiceImpl[F[_]: Sync: Clock, G[_]: Monad](
  perceptualHashingService: PerceptualHashingService[F],
  repositoryService: RepositoryService[F],
  videoPerceptualHashDao: VideoPerceptualHashDao[G],
  duplicateVideoDao: DuplicateVideoDao[G],
  snapshotDao: SnapshotDao[G],
  videoDao: VideoDao[G],
  videoPermissionDao: VideoPermissionDao[G],
  batchVideoService: BatchVideoService[F]
)(using transaction: G ~> F)
    extends BatchDuplicateDetectionService[F] {
  private val logger = Logger[BatchDuplicateDetectionServiceImpl[F, G]]

  override def detect: F[Map[FiniteDuration, Set[Set[String]]]] =
    transaction(videoPerceptualHashDao.uniqueVideoDurations)
      .flatMap { durations =>
        logger
          .info[F](s"Detecting duplicates for ${durations.size} durations")
          .productR {
            durations.toList.traverse { duration =>
              detectDuplicates(duration).map(duration -> _)
            }
          }
      }
      .map(_.toMap)

  private def detectDuplicates(duration: FiniteDuration): F[Set[Set[String]]] = {
    val snapshotDurations = VideoEnrichmentService.snapshotTimestamps(duration, SnapshotCount)
    val snapshotTimestamp =
      snapshotDurations.sorted(using FiniteDuration.FiniteDurationIsOrdered).toList(snapshotDurations.size / 2)

    logger
      .info[F](
        s"Detecting duplicates for duration=${duration.toSeconds}s, snapshotTimestamp=${snapshotTimestamp.toMillis}ms"
      )
      .productR {
        transaction {
          for {
            videoIds <- videoPerceptualHashDao.getVideoIdsByDuration(duration)
            // A hash outlives its video, which must not be grouped with the videos that are still there
            videoHashes <- videoPerceptualHashDao
              .findVideoHashesByDuration(duration)
              .map(_.filter(videoHash => videoIds.contains(videoHash.videoId)))
            videoHashIds = videoHashes.map(_.videoId).toSet
            videoIdsWithoutHashes = videoIds.filterNot(videoHashIds.contains)
          } yield videoIdsWithoutHashes -> videoHashes
        }.flatMap { case (videoIds, videoHashes) =>
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
        }.flatMap(videoHashes => calculateDuplicates(videoHashes))

      }
  }

  private def perceptualHash(
    videoId: String,
    videoDuration: FiniteDuration,
    snapshotTimestamp: FiniteDuration
  ): OptionT[F, VideoPerceptualHash] =
    snapshotHash(videoId, snapshotTimestamp)
      .semiflatMap { hash =>
        Clock[F].timestamp.map { timestamp =>
          VideoPerceptualHash(videoId, timestamp, videoDuration, hash, snapshotTimestamp)
        }
      }

  /** Computes the perceptual hash of the video's snapshot at the given timestamp, if the snapshot exists. */
  private def snapshotHash(videoId: String, snapshotTimestamp: FiniteDuration): OptionT[F, BigInt] =
    OptionT {
      transaction {
        snapshotDao
          .findByVideo(videoId, None)
          .map { snapshots =>
            snapshots.find(_.videoTimestamp.toMillis == snapshotTimestamp.toMillis)
          }
      }.flatTap {
        case None =>
          logger.info[F](s"Snapshot not found for videoId=$videoId, snapshotTimestamp=${snapshotTimestamp.toSeconds}s")
        case _ => Applicative[F].unit
      }
    }.flatMapF { snapshot =>
      repositoryService
        .read(snapshot.fileResource.path, None, None)
    }.semiflatMap { dataStream =>
      perceptualHashingService.hashImage(dataStream)
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
            (otherVideoHash, distance <= BatchDuplicateDetectionService.DifferenceThreshold)
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
    detect
      .map(_.values.flatten)
      .flatMap {
        _.toList.traverse_(handleDuplicateVideoSet)
      }
      // Every group, not only the ones detected now, so that groups left for review are checked again
      .productR(transaction(duplicateVideoDao.duplicateGroupIds))
      .flatMap(_.toList.traverse_(deleteSmallerDuplicates))

  private def handleDuplicateVideoSet(duplicateVideoSet: Set[String]): F[Unit] =
    for {
      groupId <- duplicateVideoSet.toSeq.sorted.headOption
        .toType[F, Throwable](InvalidConditionException("duplicate video set cannot be empty"))
      timestamp <- Clock[F].timestamp
      duplicateVideos = duplicateVideoSet.map(videoId =>
        DuplicateVideo(videoId = videoId, duplicateGroupId = groupId, createdAt = timestamp)
      )
      addedDuplicateVideos <- transaction {
        duplicateVideoDao
          .findByDuplicateGroupId(groupId)
          .flatTap { existingVideosForGroup =>
            if (existingVideosForGroup.isEmpty) {
              duplicateVideoSet.toSeq.traverse(duplicateVideoDao.delete).void
            } else {
              Applicative[G].unit
            }
          }
          .flatMap { existingVideosForGroup =>
            val existingVideoIdsForGroup = existingVideosForGroup.map(_.videoId).toSet
            val newDuplicateVideosToAdd =
              duplicateVideos.filter(duplicateVideo => !existingVideoIdsForGroup.contains(duplicateVideo.videoId))

            newDuplicateVideosToAdd.toList.traverse(duplicateVideoDao.insert).as(newDuplicateVideosToAdd)
          }
      }
      _ <- logger.info[F](s"Added ${addedDuplicateVideos.size} duplicate videos for group $groupId")
    } yield ()

  /** Keeps the video chosen by `videoToKeep` and deletes the rest of the duplicate group, along with their files. A
    * video that is not safe to delete stays in the group, for a user to review.
    *
    * A group member whose video is gone is dropped from the group first. Its row outlives the video, since
    * duplicate_video references video_metadata rather than video, and it would otherwise keep the group open forever.
    */
  private def deleteSmallerDuplicates(groupId: String): F[Unit] =
    transaction {
      duplicateVideoDao.findByDuplicateGroupId(groupId).flatMap { duplicateVideos =>
        NonEmptyList
          .fromList(duplicateVideos.map(_.videoId).toList)
          .fold(Applicative[G].pure(Seq.empty[Video]))(videoDao.findByIds)
          .flatTap { videos =>
            val videoIds = videos.map(_.id).toSet

            duplicateVideos
              .filterNot(duplicateVideo => videoIds.contains(duplicateVideo.videoId))
              .traverse_(duplicateVideo => duplicateVideoDao.delete(duplicateVideo.videoId))
          }
      }
    }.flatMap { videos =>
      BatchDuplicateDetectionService
        .videoToKeep(videos)
        .fold(Applicative[F].unit) { keptVideo =>
          videos
            .filter(_.id != keptVideo.id)
            .toList
            .traverse_(video => deleteDuplicate(video, keptVideo))
        }
        .productR(removeResolvedGroup(groupId))
    }

  /** Deletes the duplicate if it is safe to: see `deletionBlocker`. Its playlist memberships are handed to the kept
    * video first. A failure is logged rather than raised, so that the rest of the group is still handled: another batch
    * instance may have deleted the video already, and a video left behind is detected again in the next run.
    */
  private def deleteDuplicate(video: Video, keptVideo: Video): F[Unit] =
    deletionBlocker(video, keptVideo)
      .flatMap {
        case Some(reason) =>
          logger.info[F](s"Keeping duplicate videoId=${video.id} of videoId=${keptVideo.id}, since $reason")

        case None => deleteVerifiedDuplicate(video, keptVideo)
      }
      .handleErrorWith { throwable =>
        logger.error[F](s"Unable to delete duplicate videoId=${video.id} keeping videoId=${keptVideo.id}", throwable)
      }

  /** Returns why the duplicate must not be deleted, if it must not. A duplicate is only deleted when the same users
    * hold it and the kept video, so that no user loses a video or gains one they did not have, and when it matches the
    * kept video directly at every one of the `VerificationSnapshotTimestamps`. Detection compares a single snapshot and
    * chains matches into groups, so two videos in a group may never have been compared with each other.
    */
  private def deletionBlocker(video: Video, keptVideo: Video): F[Option[String]] =
    if (video.videoMetadata.duration != keptVideo.videoMetadata.duration)
      Applicative[F].pure(Some("their durations differ"))
    else
      transaction {
        (videoPermissionDao.find(None, Some(video.id)), videoPermissionDao.find(None, Some(keptVideo.id))).tupled
      }.flatMap { case (videoPermissions, keptVideoPermissions) =>
        if (videoPermissions.map(_.userId).toSet != keptVideoPermissions.map(_.userId).toSet)
          Applicative[F].pure(Some("different users hold them"))
        else
          BatchDuplicateDetectionService
            .verificationSnapshotTimestamps(video.videoMetadata.duration)
            .toList
            .traverse { snapshotTimestamp =>
              (
                snapshotHash(video.id, snapshotTimestamp).value,
                snapshotHash(keptVideo.id, snapshotTimestamp).value
              ).tupled
            }
            .flatMap { hashPairs =>
              hashPairs
                .traverse(_.tupled)
                .fold(Applicative[F].pure[Option[String]](Some("a snapshot to compare them with is missing"))) {
                  _.traverse { case (hash, keptHash) => perceptualHashingService.compareHashes(hash, keptHash) }
                    .map { distances =>
                      if (distances.forall(_ <= BatchDuplicateDetectionService.DifferenceThreshold)) None
                      else Some("their snapshots differ")
                    }
                }
            }
      }

  private def deleteVerifiedDuplicate(video: Video, keptVideo: Video): F[Unit] =
    transaction {
      videoDao
        .transferPlaylistMemberships(video.id, keptVideo.id)
        .productR(videoPerceptualHashDao.deleteByVideoId(video.id))
        .productR(duplicateVideoDao.delete(video.id))
    }.productR(batchVideoService.deleteById(video.id, deleteVideoFile = true))
      .productR {
        logger.info[F](
          s"Deleted duplicate videoId=${video.id} (size=${video.fileResource.size}) " +
            s"keeping videoId=${keptVideo.id} (size=${keptVideo.fileResource.size})"
        )
      }

  /** Removes the group once at most one of its videos is left, since a lone video is no longer a duplicate. */
  private def removeResolvedGroup(groupId: String): F[Unit] =
    transaction {
      duplicateVideoDao.findByDuplicateGroupId(groupId).flatMap { duplicateVideos =>
        if (duplicateVideos.size <= 1)
          duplicateVideos.traverse_(duplicateVideo => duplicateVideoDao.delete(duplicateVideo.videoId))
        else Applicative[G].unit
      }
    }

}
