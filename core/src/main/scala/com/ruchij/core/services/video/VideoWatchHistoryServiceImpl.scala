package com.ruchij.core.services.video

import cats.data.OptionT
import cats.effect.Concurrent
import cats.implicits.{toFlatMapOps, toFunctorOps}
import cats.{Monad, ~>}
import com.ruchij.core.daos.video.VideoDao
import com.ruchij.core.daos.videowatchhistory.VideoWatchHistoryDao
import com.ruchij.core.daos.videowatchhistory.models.VideoWatchHistory
import com.ruchij.core.services.video.VideoWatchHistoryServiceImpl.SameVideoSessionDuration
import com.ruchij.core.services.video.models.WatchedVideo
import com.ruchij.core.types.RandomGenerator
import org.joda.time.DateTime

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

class VideoWatchHistoryServiceImpl[F[_]: Concurrent: RandomGenerator[*[_], UUID], G[_]: Monad](
  videoWatchHistoryDao: VideoWatchHistoryDao[G],
  videoDao: VideoDao[G]
)(implicit transaction: G ~> F)
    extends VideoWatchHistoryService[F] {

  override def getWatchHistoryByUser(userId: String, pageSize: Int, pageNumber: Int): F[List[WatchedVideo]] =
    transaction {
      videoWatchHistoryDao.findBy(userId, pageSize, pageNumber)
    }.flatMap { videoWatchHistoryItems =>
      Concurrent[F].parTraverseN(30)(videoWatchHistoryItems) {
        videoWatchHistoryItem =>
          transaction {
            videoDao.findById(videoWatchHistoryItem.videoId, None)
          }.map(maybeVideo => (videoWatchHistoryItem, maybeVideo))
      }
        .map {
          watchedVideos =>
            watchedVideos.flatMap {
              case (videoWatchHistory, Some(video)) =>
                List {
                  WatchedVideo(
                    videoWatchHistory.userId,
                    video,
                    videoWatchHistory.createdAt,
                    videoWatchHistory.lastUpdatedAt,
                    videoWatchHistory.duration
                  )
                }

              case _ => List.empty
            }
        }
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
