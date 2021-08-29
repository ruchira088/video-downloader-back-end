package com.ruchij.core.services.video

import cats.data.{NonEmptyList, OptionT}
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadError, ~>}
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.models.RangeValue
import com.ruchij.core.daos.snapshot.SnapshotDao
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.daos.videometadata.models.VideoSite
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.services.video.models.VideoServiceSummary

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

class VideoServiceImpl[F[_]: MonadError[*[_], Throwable], T[_]: MonadError[*[_], Throwable]](
  repositoryService: RepositoryService[F],
  videoDao: VideoDao[T],
  videoMetadataDao: VideoMetadataDao[T],
  snapshotDao: SnapshotDao[T],
  schedulingDao: SchedulingDao[T],
  fileResourceDao: FileResourceDao[T]
)(implicit transaction: T ~> F)
    extends VideoService[F] {

  override def insert(videoMetadataKey: String, fileResourceKey: String): F[Video] =
    transaction(videoDao.insert(videoMetadataKey, fileResourceKey, FiniteDuration(0, TimeUnit.MILLISECONDS)))
      .productR(fetchById(videoMetadataKey))

  override def fetchById(videoId: String): F[Video] =
    OptionT(transaction(videoDao.findById(videoId)))
      .getOrElseF {
        ApplicativeError[F, Throwable].raiseError {
          ResourceNotFoundException(s"Unable to find video with ID: $videoId")
        }
      }

  override def search(
    term: Option[String],
    durationRange: RangeValue[FiniteDuration],
    sizeRange: RangeValue[Long],
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order,
    videoSites: Option[NonEmptyList[VideoSite]]
  ): F[Seq[Video]] =
    transaction {
      videoDao.search(term, durationRange, sizeRange, pageNumber, pageSize, sortBy, order, videoSites)
    }

  override def fetchVideoSnapshots(videoId: String): F[Seq[Snapshot]] =
    transaction {
      snapshotDao.findByVideo(videoId)
    }

  override def incrementWatchTime(videoId: String, duration: FiniteDuration): F[FiniteDuration] =
    OptionT(transaction(videoDao.incrementWatchTime(videoId, duration)))
      .getOrElseF {
        ApplicativeError[F, Throwable].raiseError {
          ResourceNotFoundException(s"Unable to find vide with ID: $videoId")
        }
      }

  override def fetchByVideoFileResourceId(videoFileResourceId: String): F[Video] =
    OptionT(transaction(videoDao.findByVideoFileResourceId(videoFileResourceId)))
      .getOrElseF {
        ApplicativeError[F, Throwable].raiseError {
          ResourceNotFoundException(s"Unable to find video for video file resource ID: $videoFileResourceId")
        }
      }

  override def update(videoId: String, maybeTitle: Option[String], maybeSize: Option[Long]): F[Video] =
    transaction {
      OptionT(videoDao.findById(videoId)).semiflatMap { video =>
        videoMetadataDao
          .update(videoId, maybeTitle, maybeSize)
          .product(fileResourceDao.update(video.fileResource.id, maybeSize))
      }.value
    }.productR(fetchById(videoId))

  override def deleteById(videoId: String, deleteVideoFile: Boolean): F[Video] =
    fetchById(videoId)
      .flatTap { video =>
        transaction {
          snapshotDao
            .findByVideo(videoId)
            .productL(snapshotDao.deleteByVideo(videoId))
            .flatTap {
              _.toList.traverse { snapshot =>
                fileResourceDao.deleteById(snapshot.fileResource.id)
              }
            }
            .productL(videoDao.deleteById(videoId))
            .productL(schedulingDao.deleteById(videoId))
            .productL(videoMetadataDao.deleteById(videoId))
            .productL(fileResourceDao.deleteById(video.videoMetadata.thumbnail.id))
            .productL(fileResourceDao.deleteById(video.fileResource.id))
        }.flatMap {
            _.toList.traverse { snapshot =>
              repositoryService.delete(snapshot.fileResource.path)
            }
          }
      }
      .flatTap { video =>
        if (deleteVideoFile) repositoryService.delete(video.fileResource.path) else Applicative[F].pure(false)
      }

  override val summary: F[VideoServiceSummary] =
    transaction {
      for {
        count <- videoDao.count
        size <- videoDao.size
        duration <- videoDao.duration
      } yield VideoServiceSummary(count, size, duration)
    }
}
