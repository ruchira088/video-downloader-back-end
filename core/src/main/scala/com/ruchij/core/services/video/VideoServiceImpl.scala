package com.ruchij.core.services.video

import cats.data.OptionT
import cats.implicits._
import cats.{MonadError, ~>}
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.scheduling.SchedulingDao
import com.ruchij.core.daos.snapshot.SnapshotDao
import com.ruchij.core.daos.snapshot.models.Snapshot
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.exceptions.ResourceNotFoundException
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.services.video.models.{DurationRange, VideoServiceSummary}

class VideoServiceImpl[F[_]: MonadError[*[_], Throwable], T[_]: MonadError[*[_], Throwable]](
  videoDao: VideoDao[T],
  videoMetadataDao: VideoMetadataDao[T],
  snapshotDao: SnapshotDao[T],
  schedulingDao: SchedulingDao[T],
  fileResourceDao: FileResourceDao[T]
)(implicit transaction: T ~> F)
    extends VideoService[F] {

  override def insert(videoMetadataKey: String, fileResourceKey: String): F[Video] =
    transaction(videoDao.insert(videoMetadataKey, fileResourceKey))
      .productR(fetchById(videoMetadataKey))

  override def fetchById(videoId: String): F[Video] =
    OptionT(transaction(videoDao.findById(videoId)))
      .getOrElseF {
        MonadError[F, Throwable].raiseError(ResourceNotFoundException(s"Unable to find video with ID: $videoId"))
      }

  override def search(term: Option[String], durationRange: DurationRange, pageNumber: Int, pageSize: Int, sortBy: SortBy, order: Order): F[Seq[Video]] =
    transaction {
      videoDao.search(term, durationRange, pageNumber, pageSize, sortBy, order)
    }

  override def fetchVideoSnapshots(videoId: String): F[Seq[Snapshot]] =
    transaction {
      snapshotDao.findByVideo(videoId)
    }

  override def update(videoId: String, title: Option[String]): F[Video] =
    transaction(videoMetadataDao.update(videoId, title))
      .productR(fetchById(videoId))

  override def deleteById(videoId: String): F[Video] =
    fetchById(videoId)
        .flatTap { video =>
          transaction {
            snapshotDao.findByVideo(videoId)
              .productL(snapshotDao.deleteByVideo(videoId))
              .flatMap(_.toList.traverse(snapshot => fileResourceDao.deleteById(snapshot.fileResource.id)))
              .productR(videoDao.deleteById(videoId))
              .productR(schedulingDao.deleteById(videoId))
              .productR(videoMetadataDao.deleteById(videoId))
              .productR(fileResourceDao.deleteById(video.videoMetadata.thumbnail.id))
              .productR(fileResourceDao.deleteById(video.fileResource.id))
          }
        }

  override val summary: F[VideoServiceSummary] =
    transaction {
      for {
        count <- videoDao.count
        size <- videoDao.size
        duration <- videoDao.duration
      }
      yield VideoServiceSummary(count, size, duration)
    }
}
