package com.ruchij.batch.services.video

import cats.data.OptionT
import cats.effect.Sync
import cats.implicits._
import cats.{ApplicativeError, MonadThrow, ~>}
import com.ruchij.core.daos.doobie.DoobieUtils.SingleUpdateOps
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.exceptions.{InvalidConditionException, ResourceNotFoundException}
import com.ruchij.core.logging.Logger
import com.ruchij.core.services.video.VideoService
import com.ruchij.core.types.Clock

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

class BatchVideoServiceImpl[F[_]: Sync: Clock, G[_]: MonadThrow](
  videoService: VideoService[F, G],
  videoDao: VideoDao[G],
  videoMetadataDao: VideoMetadataDao[G],
  fileResourceDao: FileResourceDao[G]
)(implicit transaction: G ~> F)
    extends BatchVideoService[F] {

  private val logger = Logger[BatchVideoServiceImpl[F, G]]

  override def insert(videoMetadataKey: String, fileResourceKey: String): F[Video] =
    logger
      .debug[F](s"Inserting Video videoMetadataKey=$videoMetadataKey fileResourceKey=$fileResourceKey")
      .productR(Clock[F].timestamp)
      .flatMap { timestamp =>
        transaction {
          videoDao
            .insert(videoMetadataKey, fileResourceKey, timestamp, FiniteDuration(0, TimeUnit.MILLISECONDS))
            .productR {
              OptionT(videoDao.findById(videoMetadataKey, None))
                .getOrElseF {
                  ApplicativeError[G, Throwable].raiseError {
                    InvalidConditionException {
                      s"Unable save video. Id=$videoMetadataKey fileResourceId=$fileResourceKey"
                    }
                  }
                }
            }
        }
      }
      .productL {
        logger.debug[F](
          s"Successfully inserted Video videoMetadataKey=$videoMetadataKey fileResourceKey=$fileResourceKey"
        )
      }

  override def fetchByVideoFileResourceId(videoFileResourceId: String): F[Video] =
    OptionT(transaction(videoDao.findByVideoFileResourceId(videoFileResourceId)))
      .getOrElseF {
        ApplicativeError[F, Throwable].raiseError {
          ResourceNotFoundException(s"Unable to find video for video file resource ID: $videoFileResourceId")
        }
      }

  override def incrementWatchTime(videoId: String, duration: FiniteDuration): F[FiniteDuration] =
    OptionT(transaction(videoDao.incrementWatchTime(videoId, duration)))
      .getOrElseF {
        ApplicativeError[F, Throwable].raiseError {
          ResourceNotFoundException(s"Unable to find video with ID: $videoId")
        }
      }

  override def update(videoId: String, size: Long): F[Video] =
    transaction {
      OptionT(videoDao.findById(videoId, None))
        .semiflatMap { video =>
          videoMetadataDao
            .update(videoId, None, Some(size), None)
            .one
            .productR(fileResourceDao.update(video.fileResource.id, size).one)
        }
        .productR(OptionT(videoDao.findById(videoId, None)))
        .getOrElseF {
          ApplicativeError[G, Throwable].raiseError {
            ResourceNotFoundException(s"Unable to find video with ID: $videoId")
          }
        }
    }

  override def deleteById(videoId: String, deleteVideoFile: Boolean): F[Video] =
    videoService.deleteById(videoId, deleteVideoFile)
}
