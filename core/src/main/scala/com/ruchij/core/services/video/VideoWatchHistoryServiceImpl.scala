package com.ruchij.core.services.video

import cats.data.OptionT
import cats.effect.Sync
import cats.implicits.toFlatMapOps
import cats.{Monad, ~>}
import com.ruchij.core.daos.videowatchhistory.VideoWatchHistoryDao
import com.ruchij.core.daos.videowatchhistory.models.{DetailedVideoWatchHistory, VideoWatchHistory}
import com.ruchij.core.services.video.VideoWatchHistoryServiceImpl.SameVideoSessionDuration
import com.ruchij.core.types.RandomGenerator
import org.joda.time.DateTime

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

class VideoWatchHistoryServiceImpl[F[_]: Sync: RandomGenerator[*[_], UUID], G[_]: Monad](
  videoWatchHistoryDao: VideoWatchHistoryDao[G]
)(implicit transaction: G ~> F)
    extends VideoWatchHistoryService[F] {

  override def getWatchHistoryByUser(
    userId: String,
    pageSize: Int,
    pageNumber: Int
  ): F[List[DetailedVideoWatchHistory]] =
    transaction {
      videoWatchHistoryDao.findBy(userId, pageSize, pageNumber)
    }

  override def addWatchHistory(
    userId: String,
    videoId: String,
    timestamp: DateTime,
    duration: FiniteDuration
  ): F[Unit] =
    OptionT {
      transaction {
        OptionT {
          videoWatchHistoryDao.findLastUpdatedAfter(
            userId,
            videoId,
            timestamp.minusMillis(SameVideoSessionDuration.toMillis.toInt)
          )
        }.semiflatMap { existingVideoWatchHistory =>
          val updated =
            existingVideoWatchHistory
              .copy(lastUpdatedAt = timestamp, duration = existingVideoWatchHistory.duration + duration)

          videoWatchHistoryDao.update(updated)
        }.value
      }
    }.getOrElseF {
      RandomGenerator[F, UUID].generate
        .flatMap { id =>
          val videoWatchHistory = VideoWatchHistory(id.toString, userId, videoId, timestamp, timestamp, duration)

          transaction {
            videoWatchHistoryDao.insert(videoWatchHistory)
          }
        }
    }
}

object VideoWatchHistoryServiceImpl {
  private val SameVideoSessionDuration: FiniteDuration = FiniteDuration(5, TimeUnit.MINUTES)
}
