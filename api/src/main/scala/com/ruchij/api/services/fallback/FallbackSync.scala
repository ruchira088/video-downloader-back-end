package com.ruchij.api.services.fallback

import cats.effect.Async
import cats.~>
import com.ruchij.api.config.FallbackSyncSettings
import com.ruchij.api.daos.user.DoobieUserDao
import com.ruchij.api.services.fallback.aws._
import com.ruchij.api.services.fallback.models.FallbackSyncRequest
import com.ruchij.api.services.scheduling.ApiSchedulingService
import com.ruchij.core.daos.permission.DoobieVideoPermissionDao
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.kv.KeyValueStore
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.{PubSub, Subscriber}
import com.ruchij.core.types.Clock
import doobie.free.connection.ConnectionIO
import fs2.Stream

import scala.concurrent.duration._

final case class FallbackSyncResources[F[_]](
  settings: FallbackSyncSettings,
  awsClients: FallbackSyncAwsClients,
  fallbackSyncRequestPubSub: PubSub[F, FallbackSyncRequest]
)

object FallbackSync {
  // Shared by every API instance, so each message is handled by one of them.
  val SubscriberGroupId = "fallback-sync"

  private val logger = Logger[FallbackSync.type]

  def stream[F[_]: Async: Clock](
    resources: FallbackSyncResources[F],
    keyValueStore: KeyValueStore[F],
    schedulingService: ApiSchedulingService[F],
    scheduledVideoDownloadSubscriber: Subscriber[F, ScheduledVideoDownload],
    instanceId: String
  )(implicit transaction: ConnectionIO ~> F): Stream[F, Unit] = {
    val settings = resources.settings
    val fallbackSyncDao = new DoobieFallbackSyncDao(DoobieSchedulingDao, DoobieVideoPermissionDao)
    val transport = new SqsFallbackSyncTransport[F](resources.awsClients.sqs, settings.mainToFallbackQueueUrl)
    val coordination = new FallbackSyncCoordination[F](keyValueStore)

    val publisher = new FallbackSyncPublisher[F, ConnectionIO](fallbackSyncDao, transport, coordination)
    val reconciler =
      new FallbackReconciler[F, ConnectionIO](
        new DynamoDbFallbackManifestReader[F](resources.awsClients.dynamoDb, settings.tableName),
        fallbackSyncDao,
        transport,
        coordination,
        instanceId
      )
    val consumer =
      new FallbackRequestConsumer[F, ConnectionIO](
        new SqsFallbackRequestQueue[F](resources.awsClients.sqs, settings.fallbackToMainQueueUrl),
        schedulingService,
        DoobieUserDao,
        fallbackSyncDao,
        transport
      )

    Stream(
      resilient("scheduled-video-download publisher pipeline") {
        publisher.pipeline(scheduledVideoDownloadSubscriber, SubscriberGroupId)(_.videoMetadata.id)
      },
      resilient("fallback-sync-request publisher pipeline") {
        publisher.pipeline(resources.fallbackSyncRequestPubSub, SubscriberGroupId)(_.videoId)
      },
      resilient("reconciler")(reconciler.run()),
      resilient("request consumer")(consumer.run)
    ).parJoinUnbounded
  }

  /** `stream(...)` is started in a fiber that nobody joins (see `ApiApp.program`), so a component stream that ends
    * -- successfully or by raising -- would silently stop syncing forever with nothing to notice. Restarting just
    * the failed component after a delay, instead of letting the failure propagate out of the `parJoinUnbounded`
    * stream and end every other component too, keeps the rest of fallback sync alive. */
  private[fallback] def resilient[F[_]: Async, A](name: String, restartDelay: FiniteDuration = 30.seconds)(
    stream: Stream[F, A]
  ): Stream[F, A] =
    stream.handleErrorWith { error =>
      Stream.exec {
        logger.error[F](s"Fallback sync component $name failed; restarting in $restartDelay", error)
      } ++ Stream.sleep_(restartDelay) ++ resilient(name, restartDelay)(stream)
    }
}
