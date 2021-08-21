package com.ruchij.api.services.background

import cats.effect.{Concurrent, Fiber, Timer}
import cats.implicits._
import com.ruchij.api.services.scheduling.ApiSchedulingService
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.Subscriber
import com.ruchij.core.messaging.models.CommittableRecord
import com.ruchij.core.services.scheduling.models.DownloadProgress
import fs2.Stream
import fs2.concurrent.Topic

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class BackgroundServiceImpl[F[_]: Timer: Concurrent, M[_]](
  apiSchedulingService: ApiSchedulingService[F],
  downloadProgressSubscriber: Subscriber[F, CommittableRecord[M, *], DownloadProgress],
  downloadProgressTopic: Topic[F, Option[DownloadProgress]],
  subscriberGroupId: String
) extends BackgroundService[F] {

  override type Result = Unit

  private val logger = Logger[BackgroundServiceImpl[F, M]]

  override val downloadProgress: Stream[F, DownloadProgress] =
    downloadProgressTopic.subscribe(Int.MaxValue).collect { case Some(value) => value }

  private val publishToTopic: Stream[F, Unit] =
    downloadProgressSubscriber.subscribe(subscriberGroupId)
      .evalTap {
        case CommittableRecord(value, _) => downloadProgressTopic.publish1(Some(value))
      }
      .groupWithin(20, 5 seconds)
      .evalMap {
        chunk =>
          downloadProgressSubscriber.commit(chunk)
            .productR {
              logger.debug[F] {
                s"DownloadProgressSubscriber(groupId=$subscriberGroupId) committed ${chunk.size} messages"
              }
            }
      }

  private val updateScheduledVideoDownloads: Stream[F, ScheduledVideoDownload] =
    downloadProgress
      .groupWithin(Int.MaxValue, 5 seconds)
      .evalMap {
        _.toList
          .groupBy(_.videoId)
          .view
          .mapValues(_.maxByOption(_.bytes))
          .collect { case (_, Some(progress)) => progress }
          .toList
          .traverse {
            case DownloadProgress(videoId, _, bytes) =>
              apiSchedulingService.updateDownloadProgress(videoId, bytes)
          }
      }
      .flatMap { scheduledVideoDownloads =>
        Stream.emits[F, ScheduledVideoDownload](scheduledVideoDownloads)
      }

  override val run: F[Fiber[F, Unit]] =
    Concurrent[F].start {
      publishToTopic.concurrently(updateScheduledVideoDownloads).compile.drain
    }
}

object BackgroundServiceImpl {
  def create[F[_]: Concurrent: Timer, M[_]](
    apiSchedulingService: ApiSchedulingService[F],
    downloadProgressSubscriber: Subscriber[F, CommittableRecord[M, *], DownloadProgress],
    subscriberGroupId: String
  ): F[BackgroundServiceImpl[F, M]] =
    Topic[F, Option[DownloadProgress]](None).map {
      downloadProgressTopic =>
        new BackgroundServiceImpl[F, M](apiSchedulingService, downloadProgressSubscriber, downloadProgressTopic, subscriberGroupId)
    }
}
