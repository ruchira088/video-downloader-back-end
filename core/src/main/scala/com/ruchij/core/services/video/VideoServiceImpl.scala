package com.ruchij.core.services.video

import cats.data.{NonEmptyList, OptionT}
import cats.effect.Sync
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadError, ~>}
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.scheduling.models.RangeValue
import com.ruchij.core.daos.snapshot.SnapshotDao
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.title.VideoTitleDao
import com.ruchij.core.daos.title.models.VideoTitle
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.daos.videometadata.models.VideoSite
import com.ruchij.core.exceptions.{InvalidConditionException, ResourceNotFoundException}
import com.ruchij.core.logging.Logger
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.services.video.models.VideoServiceSummary
import org.http4s.Uri

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

class VideoServiceImpl[F[_]: Sync, T[_]: MonadError[*[_], Throwable]](
  repositoryService: RepositoryService[F],
  videoDao: VideoDao[T],
  videoMetadataDao: VideoMetadataDao[T],
  snapshotDao: SnapshotDao[T],
  schedulingDao: SchedulingDao[T],
  fileResourceDao: FileResourceDao[T],
  videoTitleDao: VideoTitleDao[T]
)(implicit transaction: T ~> F)
    extends VideoService[F] {

  private val logger = Logger[VideoServiceImpl[F, T]]

  override def insert(videoMetadataKey: String, fileResourceKey: String): F[Video] =
    logger.debug[F](s"Inserting Video videoMetadataKey=$videoMetadataKey fileResourceKey=$fileResourceKey")
      .productR {
        transaction(videoDao.insert(videoMetadataKey, fileResourceKey, FiniteDuration(0, TimeUnit.MILLISECONDS)))
          .productR(fetchById(videoMetadataKey, None))
      }
      .productL {
        logger.debug[F](s"Successfully inserted Video videoMetadataKey=$videoMetadataKey fileResourceKey=$fileResourceKey")
      }

  private def userScopedVideoTitle(video: Video, userId: String): T[Video] =
    OptionT(videoTitleDao.find(video.videoMetadata.id, userId))
      .map(videoTitle => video.copy(video.videoMetadata.copy(title = videoTitle.title)))
      .getOrElse(video)

  override def fetchById(videoId: String, maybeUserId: Option[String]): F[Video] =
    transaction {
      OptionT(videoDao.findById(videoId, maybeUserId))
        .semiflatMap {
          video =>
            maybeUserId.fold(Applicative[T].pure(video)) { userId => userScopedVideoTitle(video, userId) }
        }
        .getOrElseF {
          ApplicativeError[T, Throwable].raiseError {
            ResourceNotFoundException(s"Unable to find video with ID: $videoId")
          }
        }
    }

  override def search(
    term: Option[String],
    videoUrls: Option[NonEmptyList[Uri]],
    durationRange: RangeValue[FiniteDuration],
    sizeRange: RangeValue[Long],
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order,
    videoSites: Option[NonEmptyList[VideoSite]],
    maybeUserId: Option[String]
  ): F[Seq[Video]] =
    transaction {
      videoDao.search(term, videoUrls, durationRange, sizeRange, pageNumber, pageSize, sortBy, order, videoSites, maybeUserId)
        .flatMap { videos =>
          maybeUserId.fold(Applicative[T].pure(videos)) { userId =>
            videos.traverse(video => userScopedVideoTitle(video, userId))
          }
        }
    }

  override def fetchVideoSnapshots(videoId: String, maybeUserId: Option[String]): F[Seq[Snapshot]] =
    transaction {
      snapshotDao.findByVideo(videoId, maybeUserId)
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

  override def update(videoId: String, maybeTitle: Option[String], maybeSize: Option[Long], maybeUserId: Option[String]): F[Video] =
    transaction {
      OptionT(videoDao.findById(videoId, maybeUserId))
        .semiflatMap { video =>
          maybeUserId.product(maybeTitle) match {
            case Some((userId, title)) =>
              OptionT(videoTitleDao.find(videoId, userId))
                .foldF(videoTitleDao.insert(VideoTitle(videoId, userId, title))) {
                  _ => videoTitleDao.update(videoId, userId, title)
                }
                .productR {
                  if (maybeSize.isEmpty) Applicative[T].pure((): Unit)
                  else ApplicativeError[T, Throwable].raiseError[Unit] {
                    InvalidConditionException("Users cannot update the video file size")
                  }
                }

            case _ =>
              videoMetadataDao
                .update(videoId, maybeTitle, maybeSize)
                .product(fileResourceDao.update(video.fileResource.id, maybeSize))
                .as((): Unit)
          }
        }
        .value
    }
      .productR(fetchById(videoId, maybeUserId))

  override def deleteById(videoId: String, maybeUserId: Option[String], deleteVideoFile: Boolean): F[Video] =
    fetchById(videoId, maybeUserId)
      .flatTap { video =>
        transaction {
          snapshotDao
            .findByVideo(videoId, maybeUserId)
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
        sites <- videoDao.sites
      } yield VideoServiceSummary(count, size, duration, sites)
    }
}
