package com.ruchij.api.services.background

import cats.effect.kernel.Async
import cats.effect.{Concurrent, Fiber, Sync}
import cats.implicits._
import com.ruchij.api.services.health.models.messaging.HealthCheckMessage
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
import scala.reflect.{ClassTag, classTag}

class BackgroundServiceImpl[F[_]: Async, M[_]](
  apiSchedulingService: ApiSchedulingService[F],
  downloadProgressSubscriber: Subscriber[F, CommittableRecord[M, *], DownloadProgress],
  downloadProgressTopic: Topic[F, DownloadProgress],
  healthCheckSubscriber: Subscriber[F, CommittableRecord[M, *], HealthCheckMessage],
  healthCheckTopic: Topic[F, HealthCheckMessage],
  scheduleVideoDownloadsSubscriber: Subscriber[F, CommittableRecord[M, *], ScheduledVideoDownload],
  scheduleVideoDownloadsTopic: Topic[F, ScheduledVideoDownload],
  subscriberGroupId: String
) extends BackgroundService[F] {

  override type Result = Unit

  private val logger = Logger[BackgroundServiceImpl[F, M]]

  override val downloadProgress: Stream[F, DownloadProgress] =
    downloadProgressTopic.subscribe(Int.MaxValue)

  override val healthChecks: Stream[F, HealthCheckMessage] =
    healthCheckTopic.subscribe(Int.MaxValue)

  override val updates: Stream[F, ScheduledVideoDownload] =
    scheduleVideoDownloadsTopic.subscribe(Int.MaxValue)

  private val publishToHealthCheckTopic: Stream[F, Unit] =
    publishToTopic(healthCheckSubscriber, healthCheckTopic)

  private val publishToDownloadProgressTopic: Stream[F, Unit] =
    publishToTopic(downloadProgressSubscriber, downloadProgressTopic)

  private val publishToScheduleVideoDownloadTopic: Stream[F, Unit] =
    publishToTopic(scheduleVideoDownloadsSubscriber, scheduleVideoDownloadsTopic)

  private def publishToTopic[A: ClassTag](subscriber: Subscriber[F, CommittableRecord[M, *], A], topic: Topic[F, A]): Stream[F, Unit] =
    subscriber
      .subscribe(subscriberGroupId)
      .evalTap {
        committableRecord => topic.publish1(committableRecord.value)
      }
      .groupWithin(20, 5 seconds)
      .evalMap {
        chunk =>
          subscriber.commit(chunk)
            .productR {
              Sync[F].delay(classTag[A].runtimeClass.getSimpleName)
                .flatMap {
                  className =>
                    logger.debug[F] {
                      s"${className}Subscriber(groupId=$subscriberGroupId) committed ${chunk.size} messages"
                    }
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
          .collect { case (_, Some(videoDownloadProgress)) => videoDownloadProgress }
          .toList
          .traverse {
            case DownloadProgress(videoId, timestamp, bytes) =>
              apiSchedulingService.updateDownloadProgress(videoId, timestamp, bytes)
          }
      }
      .flatMap { scheduledVideoDownloads =>
        Stream.emits[F, ScheduledVideoDownload](scheduledVideoDownloads)
      }

  override val run: F[Fiber[F, Throwable, Unit]] =
    Concurrent[F].start {
      updateScheduledVideoDownloads
        .concurrently(publishToDownloadProgressTopic)
        .concurrently(publishToScheduleVideoDownloadTopic)
        .concurrently(publishToHealthCheckTopic)
        .compile
        .drain
    }
}

object BackgroundServiceImpl {
  def create[F[_]: Async, M[_]](
    apiSchedulingService: ApiSchedulingService[F],
    downloadProgressSubscriber: Subscriber[F, CommittableRecord[M, *], DownloadProgress],
    healthCheckSubscriber: Subscriber[F, CommittableRecord[M, *], HealthCheckMessage],
    scheduledVideoDownloadsSubscriber: Subscriber[F, CommittableRecord[M, *], ScheduledVideoDownload],
    subscriberGroupId: String
  ): F[BackgroundServiceImpl[F, M]] = {
    for {
      downloadProgressTopic <- Topic[F, DownloadProgress]
      healthCheckTopic <- Topic[F, HealthCheckMessage]
      scheduleVideoDownloadsTopic <- Topic[F, ScheduledVideoDownload]
    } yield new BackgroundServiceImpl[F, M](
      apiSchedulingService,
      downloadProgressSubscriber,
      downloadProgressTopic,
      healthCheckSubscriber,
      healthCheckTopic,
      scheduledVideoDownloadsSubscriber,
      scheduleVideoDownloadsTopic,
      subscriberGroupId
    )
  }
}
