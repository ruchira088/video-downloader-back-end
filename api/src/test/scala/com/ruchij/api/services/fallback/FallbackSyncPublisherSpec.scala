package com.ruchij.api.services.fallback

import cats.effect.IO
import com.ruchij.api.services.fallback.FallbackSyncStubs._
import com.ruchij.api.services.fallback.FallbackSyncTestData.{capturedAt, scheduledVideoDownload}
import com.ruchij.api.services.fallback.models.{ScheduledVideoRemoval, ScheduledVideoUpsert}
import com.ruchij.core.kv.InMemoryKeyValueStore
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.Providers
import com.ruchij.core.types.Clock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class FallbackSyncPublisherSpec extends AnyFlatSpec with Matchers {
  implicit val clock: Clock[IO] = Providers.stubClock[IO](capturedAt)

  private val noDelays = List(Duration.Zero, Duration.Zero, Duration.Zero)

  "FallbackSyncPublisher.messagesFor" should "turn found rows into upserts and missing rows into removals" in runIO {
    for {
      dao <- StubFallbackSyncDao(SyncedVideo(scheduledVideoDownload("video-1"), List("user-1")))
      transport <- RecordingTransport()
      coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
      publisher = new FallbackSyncPublisher[IO, IO](dao, transport, coordination, retryDelays = noDelays)
      messages <- publisher.messagesFor(List("video-1", "missing", "video-1"))
    } yield {
      messages.size mustBe 2
      messages.head mustBe a[ScheduledVideoUpsert]
      messages(1) mustBe ScheduledVideoRemoval("missing", capturedAt)
    }
  }

  "FallbackSyncPublisher.publish" should "retry a failing send" in runIO {
    for {
      dao <- StubFallbackSyncDao(SyncedVideo(scheduledVideoDownload("video-1"), List("user-1")))
      transport <- FlakyTransport(failures = 2)
      coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
      publisher = new FallbackSyncPublisher[IO, IO](dao, transport, coordination, retryDelays = noDelays)
      _ <- publisher.publish(List("video-1"))
      sent <- transport.recording.messages
      flagged <- coordination.isReconcileNeeded
    } yield {
      sent.size mustBe 1
      flagged mustBe false
    }
  }

  it should "flag a reconcile instead of failing when every retry fails" in runIO {
    for {
      dao <- StubFallbackSyncDao(SyncedVideo(scheduledVideoDownload("video-1"), List("user-1")))
      transport <- FlakyTransport(failures = 10)
      coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
      publisher = new FallbackSyncPublisher[IO, IO](dao, transport, coordination, retryDelays = noDelays)
      result <- publisher.publish(List("video-1")).attempt
      flagged <- coordination.isReconcileNeeded
    } yield {
      result mustBe Right(())
      flagged mustBe true
    }
  }
}
