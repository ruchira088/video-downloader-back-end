package com.ruchij.api.services.fallback

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.{Foldable, Functor}
import com.ruchij.api.services.fallback.FallbackSyncStubs._
import com.ruchij.api.services.fallback.FallbackSyncTestData.{capturedAt, scheduledVideoDownload}
import com.ruchij.api.services.fallback.aws.FallbackSyncTransport
import com.ruchij.api.services.fallback.models.{MainToFallbackMessage, ScheduledVideoRemoval, ScheduledVideoUpsert}
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.kv.InMemoryKeyValueStore
import com.ruchij.core.messaging.Subscriber
import com.ruchij.core.messaging.inmemory.Fs2PubSub
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import org.scalatest.flatspec.AnyFlatSpec
import fs2.Stream
import fs2.concurrent.Topic
import org.scalatest.matchers.must.Matchers

import java.time.Instant
import scala.concurrent.duration._

class FallbackSyncPublisherSpec extends AnyFlatSpec with Matchers {
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

  it should "turn removed ids into removals without reading the database, even for rows that still exist" in runIO {
    for {
      dao <- StubFallbackSyncDao(
        SyncedVideo(scheduledVideoDownload("video-1"), List("user-1")),
        SyncedVideo(scheduledVideoDownload("video-2"), List("user-1"))
      )
      transport <- RecordingTransport()
      coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
      publisher = new FallbackSyncPublisher[IO, IO](dao, transport, coordination, retryDelays = noDelays)
      messages <- publisher.messagesFor(List("video-1", "video-2", "video-1"), removedVideoIds = Set("video-1"))
    } yield {
      messages.size mustBe 2
      messages.head mustBe ScheduledVideoRemoval("video-1", capturedAt)
      messages(1) mustBe a[ScheduledVideoUpsert]
    }
  }

  it should "stamp each message with the database clock read in the same transaction as its row" in runIO {
    val first = Instant.parse("2026-09-26T09:00:00.000001Z")
    val second = Instant.parse("2026-09-26T09:00:00.000002Z")

    for {
      dao <- StubFallbackSyncDao(SyncedVideo(scheduledVideoDownload("video-1"), List("user-1")))
      _ <- dao.setTimestamps(first, second)
      transport <- RecordingTransport()
      coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
      publisher = new FallbackSyncPublisher[IO, IO](dao, transport, coordination, retryDelays = noDelays)
      messages <- publisher.messagesFor(List("video-1", "missing"))
    } yield {
      messages.collect { case upsert: ScheduledVideoUpsert => upsert.capturedAt } mustBe List(first)
      messages.collect { case removal: ScheduledVideoRemoval => removal } mustBe List(ScheduledVideoRemoval("missing", second))
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

  /** Records commits in the same event log as the transport's sends, so the test can check their order. */
  private final class CommitRecordingSubscriber[A](delegate: Fs2PubSub[IO, A], events: Ref[IO, List[String]])
      extends Subscriber[IO, A] {
    override type C[X] = X

    override def subscribe(groupId: String): Stream[IO, A] = delegate.subscribe(groupId)

    override def commit[H[_]: Foldable: Functor](values: H[A]): IO[Unit] = events.update(_ :+ "commit")

    override def extractValue(ca: A): A = ca
  }

  "FallbackSyncPublisher.pipeline" should "send one message per distinct video in a window and commit after sending" in
    runIO {
      val events =
        List(
          scheduledVideoDownload("video-1"),
          scheduledVideoDownload("video-1"),
          scheduledVideoDownload("missing"),
          scheduledVideoDownload("video-2", SchedulingStatus.Deleted)
        )

      val test =
        for {
          dao <- StubFallbackSyncDao(
            SyncedVideo(scheduledVideoDownload("video-1"), List("user-1")),
            SyncedVideo(scheduledVideoDownload("video-2"), List("user-1"))
          )
          eventLog <- Ref.of[IO, List[String]](Nil)
          recording <- RecordingTransport()
          transport = new FallbackSyncTransport[IO] {
            override def send(messages: List[MainToFallbackMessage]): IO[Unit] =
              eventLog.update(_ :+ "send") *> recording.send(messages)
          }
          topic <- Topic[IO, ScheduledVideoDownload]
          subscriber = new CommitRecordingSubscriber(new Fs2PubSub[IO, ScheduledVideoDownload](topic), eventLog)
          coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
          // The batch fills up with the four events, so the window never has to elapse
          publisher = new FallbackSyncPublisher[IO, IO](
            dao,
            transport,
            coordination,
            window = 1.second,
            maxBatchSize = events.size,
            retryDelays = noDelays
          )
          pipeline <- publisher
            .pipeline(subscriber, "test-group")(_.videoMetadata.id, _.status == SchedulingStatus.Deleted)
            .take(1)
            .compile
            .drain
            .start
          _ <- topic.subscribers.find(_ > 0).compile.drain
          _ <- Stream.emits(events).through(topic.publish).compile.drain
          _ <- pipeline.joinWithNever
          sent <- recording.messages
          log <- eventLog.get
        } yield {
          sent.size mustBe 3
          sent.collect { case upsert: ScheduledVideoUpsert => upsert.videoId } mustBe List("video-1")
          sent(1) mustBe ScheduledVideoRemoval("missing", capturedAt)
          sent(2) mustBe ScheduledVideoRemoval("video-2", capturedAt)
          log mustBe List("send", "commit")
        }

      test.withTimeout(10.seconds)
    }
}
