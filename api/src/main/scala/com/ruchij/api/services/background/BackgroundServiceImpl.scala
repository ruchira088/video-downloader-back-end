package com.ruchij.api.services.background

import cats.effect.kernel.Async
import cats.effect.{Concurrent, Fiber, Sync}
import cats.implicits._
import com.ruchij.api.services.health.models.messaging.HealthCheckMessage
import com.ruchij.api.services.scheduling.ApiSchedulingService
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.Subscriber
import com.ruchij.core.services.scheduling.models.DownloadProgress
import fs2.Stream
import fs2.concurrent.Topic

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.reflect.{ClassTag, classTag}

class BackgroundServiceImpl[F[_]: Async](
  apiSchedulingService: ApiSchedulingService[F],
  downloadProgressSubscriber: Subscriber[F, DownloadProgress],
  downloadProgressTopic: Topic[F, DownloadProgress],
  healthCheckSubscriber: Subscriber[F, HealthCheckMessage],
  healthCheckTopic: Topic[F, HealthCheckMessage],
  scheduleVideoDownloadsSubscriber: Subscriber[F, ScheduledVideoDownload],
  scheduleVideoDownloadsTopic: Topic[F, ScheduledVideoDownload],
  subscriberGroupId: String
) extends BackgroundService[F] {

  override type Result = Unit

  private val logger = Logger[BackgroundServiceImpl[F]]

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

  private def publishToTopic[A: ClassTag](subscriber: Subscriber[F, A], topic: Topic[F, A]): Stream[F, Unit] =
    subscriber
      .subscribe(subscriberGroupId)
      .evalTap {
        ca => topic.publish1(subscriber.extractValue(ca))
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
  def create[F[_]: Async](
    apiSchedulingService: ApiSchedulingService[F],
    downloadProgressSubscriber: Subscriber[F, DownloadProgress],
    healthCheckSubscriber: Subscriber[F, HealthCheckMessage],
    scheduledVideoDownloadsSubscriber: Subscriber[F, ScheduledVideoDownload],
    subscriberGroupId: String
  ): F[BackgroundServiceImpl[F]] = {
    for {
      downloadProgressTopic <- Topic[F, DownloadProgress]
      healthCheckTopic <- Topic[F, HealthCheckMessage]
      scheduleVideoDownloadsTopic <- Topic[F, ScheduledVideoDownload]
    } yield new BackgroundServiceImpl[F](
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
