package com.ruchij.api.services.background

import cats.effect.{Concurrent, Timer}
import cats.implicits._
import com.ruchij.core.messaging.Subscriber
import com.ruchij.core.messaging.kafka.KafkaSubscriber.CommittableRecord
import com.ruchij.core.services.scheduling.SchedulingService
import com.ruchij.core.services.scheduling.models.DownloadProgress

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class BackgroundServiceImpl[F[_]: Timer: Concurrent](
  downloadProgressSubscriber: Subscriber[F, CommittableRecord[F, *], DownloadProgress],
  schedulingService: SchedulingService[F],
  subscriberGroupId: String
) extends BackgroundService[F] {

  override type Result = Unit

  override val run: F[Unit] =
    downloadProgressSubscriber
      .subscribe(subscriberGroupId)
      .groupWithin(Int.MaxValue, 5 seconds)
      .evalMap { chunk =>
        chunk.toList
          .groupBy(_.value.videoId)
          .view
          .mapValues(_.maxByOption(_.value.updatedAt.getMillis))
          .collect {
            case (_, Some(committableRecord)) => committableRecord.value
          }
          .toList
          .traverse {
            case DownloadProgress(videoId, _, bytes) =>
              schedulingService.publishDownloadProgress(videoId, bytes)
          }
      }
      .compile
      .drain
}
