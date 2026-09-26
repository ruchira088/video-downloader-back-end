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

  it should "turn a deletion into a removal while its row still awaits the hard delete" in runIO {
    // scheduledVideoDownload is scheduled at 2026-09-25T21:04, before this admin delete
    val deletedAt = Instant.parse("2026-09-26T08:00:00Z")

    for {
      dao <- StubFallbackSyncDao(
        SyncedVideo(scheduledVideoDownload("video-1"), List("user-1")),
        SyncedVideo(scheduledVideoDownload("video-2"), List("user-1"))
      )
      transport <- RecordingTransport()
      coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
      publisher = new FallbackSyncPublisher[IO, IO](dao, transport, coordination, retryDelays = noDelays)
      messages <- publisher.messagesFor(List("video-1", "video-2", "video-1"), Map("video-1" -> deletedAt))
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
      messages.collect { case removal: ScheduledVideoRemoval => removal } mustBe
        List(ScheduledVideoRemoval("missing", second))
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

  /** Runs the pipeline over one window holding exactly `events`, returning what was sent and the send/commit log. */
  private def runPipeline(
    dao: StubFallbackSyncDao,
    events: List[ScheduledVideoDownload]
  ): IO[(List[MainToFallbackMessage], List[String])] = {
    val test =
      for {
        eventLog <- Ref.of[IO, List[String]](Nil)
        recording <- RecordingTransport()
        transport = new FallbackSyncTransport[IO] {
          override def send(messages: List[MainToFallbackMessage]): IO[Unit] =
            eventLog.update(_ :+ "send") *> recording.send(messages)
        }
        topic <- Topic[IO, ScheduledVideoDownload]
        subscriber = new CommitRecordingSubscriber(new Fs2PubSub[IO, ScheduledVideoDownload](topic), eventLog)
        coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
        // The batch fills up with the events, so the window never has to elapse
        publisher = new FallbackSyncPublisher[IO, IO](
          dao,
          transport,
          coordination,
          window = 1.second,
          maxBatchSize = events.size,
          retryDelays = noDelays
        )
        pipeline <- publisher
          .pipeline(subscriber, "test-group")(
            _.videoMetadata.id,
            event => Option.when(event.status == SchedulingStatus.Deleted)(event.lastUpdatedAt)
          )
          .take(1)
          .compile
          .drain
          .start
        _ <- topic.subscribers.find(_ > 0).compile.drain
        _ <- Stream.emits(events).through(topic.publish).compile.drain
        _ <- pipeline.joinWithNever
        sent <- recording.messages
        log <- eventLog.get
      } yield (sent, log)

    test.withTimeout(10.seconds)
  }

  private def deleted(videoId: String, at: Instant): ScheduledVideoDownload =
    scheduledVideoDownload(videoId, SchedulingStatus.Deleted).copy(lastUpdatedAt = at)

  private def scheduledAt(videoId: String, at: Instant): SyncedVideo = {
    val video = scheduledVideoDownload(videoId)
    SyncedVideo(video.copy(scheduledAt = at, lastUpdatedAt = at), List("user-1"))
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

      for {
        dao <- StubFallbackSyncDao(
          SyncedVideo(scheduledVideoDownload("video-1"), List("user-1")),
          SyncedVideo(scheduledVideoDownload("video-2"), List("user-1"))
        )
        (sent, log) <- runPipeline(dao, events)
      } yield {
        sent.size mustBe 3
        sent.collect { case upsert: ScheduledVideoUpsert => upsert.videoId } mustBe List("video-1")
        sent(1) mustBe ScheduledVideoRemoval("missing", capturedAt)
        sent(2) mustBe ScheduledVideoRemoval("video-2", capturedAt)
        log mustBe List("send", "commit")
      }
    }

  it should "send a removal for an admin delete whose row still awaits batch's hard delete" in runIO {
    val scheduled = Instant.parse("2026-09-26T08:00:00Z")

    for {
      dao <- StubFallbackSyncDao(scheduledAt("video-1", scheduled))
      (sent, _) <- runPipeline(dao, List(deleted("video-1", scheduled.plusSeconds(60))))
    } yield sent mustBe List(ScheduledVideoRemoval("video-1", capturedAt))
  }

  it should "upsert a video whose Deleted event is replayed after its URL was scheduled again" in runIO {
    val deletedAt = Instant.parse("2026-09-26T08:00:00Z")

    for {
      // The row was hard-deleted and the (deterministic) id scheduled again, after the original delete
      dao <- StubFallbackSyncDao(scheduledAt("video-1", deletedAt.plusSeconds(3600)))
      (sent, _) <- runPipeline(dao, List(deleted("video-1", deletedAt)))
    } yield sent.collect { case upsert: ScheduledVideoUpsert => upsert.videoId } mustBe List("video-1")
  }

  it should "upsert a video scheduled again after its Deleted event in the same window" in runIO {
    val deletedAt = Instant.parse("2026-09-26T08:00:00Z")
    val rescheduled = scheduledAt("video-1", deletedAt.plusSeconds(10))

    for {
      dao <- StubFallbackSyncDao(rescheduled)
      (sent, _) <- runPipeline(dao, List(deleted("video-1", deletedAt), rescheduled.scheduledVideoDownload))
    } yield sent.collect { case upsert: ScheduledVideoUpsert => upsert.videoId } mustBe List("video-1")
  }

  it should "send a removal for a Deleted event followed in its window by an update of the row awaiting deletion" in
    runIO {
      val scheduled = Instant.parse("2026-09-26T08:00:00Z")
      val deletedAt = scheduled.plusSeconds(60)
      // e.g. an admin retrying or changing the status of the video before batch hard-deletes its row
      val updated =
        scheduledAt("video-1", scheduled).scheduledVideoDownload.copy(lastUpdatedAt = deletedAt.plusSeconds(5))

      for {
        dao <- StubFallbackSyncDao(scheduledAt("video-1", scheduled))
        (sent, _) <- runPipeline(dao, List(deleted("video-1", deletedAt), updated))
      } yield sent mustBe List(ScheduledVideoRemoval("video-1", capturedAt))
    }

  it should "judge a row against the latest of several Deleted events for it in one window" in runIO {
    val scheduled = Instant.parse("2026-09-26T08:00:00Z")

    for {
      // Scheduled again between two deletions, so only the later deletion covers it
      dao <- StubFallbackSyncDao(scheduledAt("video-1", scheduled))
      (sent, _) <- runPipeline(
        dao,
        List(deleted("video-1", scheduled.plusSeconds(60)), deleted("video-1", scheduled.minusSeconds(60)))
      )
    } yield sent mustBe List(ScheduledVideoRemoval("video-1", capturedAt))
  }
}
