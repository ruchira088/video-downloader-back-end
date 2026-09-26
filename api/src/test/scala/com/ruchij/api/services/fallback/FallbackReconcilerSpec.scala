package com.ruchij.api.services.fallback

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.testkit.TestControl
import com.ruchij.api.services.fallback.FallbackSyncStubs._
import com.ruchij.api.services.fallback.FallbackSyncTestData.{capturedAt, scheduledVideoDownload}
import com.ruchij.api.services.fallback.aws.{FallbackManifestReader, FallbackSyncTransport, ManifestEntry}
import com.ruchij.api.services.fallback.models.{MainToFallbackMessage, ScheduledVideoRemoval, ScheduledVideoUpsert}
import com.ruchij.core.kv.{InMemoryKeyValueStore, KeyValueStore}
import com.ruchij.core.kv.codecs.{KVDecoder, KVEncoder}
import com.ruchij.core.test.IOSupport._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.time.Instant
import scala.concurrent.duration._

class FallbackReconcilerSpec extends AnyFlatSpec with Matchers {
  private def manifestOf(entries: (String, ManifestEntry)*): FallbackManifestReader[IO] =
    new FallbackManifestReader[IO] {
      override val manifest: IO[Map[String, ManifestEntry]] = IO.pure(entries.toMap)
    }

  /** Fails the first `failures` reads of the reconcile flag, then delegates. Simulates the key-value store (e.g.
    * Redis) being unreachable for the flag check specifically (the scenario the fix addresses) while everything
    * else, including the reconcile lock, keeps working normally. */
  private final class FlakyKeyValueStore(delegate: InMemoryKeyValueStore[IO], remainingFailures: Ref[IO, Int])
      extends KeyValueStore[IO] {
    override type InsertionResult = Boolean
    override type DeletionResult = Boolean

    override def get[K: KVEncoder[IO, *], V: KVDecoder[IO, *]](key: K): IO[Option[V]] =
      if (key != FallbackSyncCoordination.ReconcileNeededKey) delegate.get(key)
      else
        remainingFailures.modify(n => (math.max(n - 1, 0), n)).flatMap { n =>
          if (n > 0) IO.raiseError(new RuntimeException("Key-value store unavailable")) else delegate.get(key)
        }

    override def put[K: KVEncoder[IO, *], V: KVEncoder[IO, *]](
      key: K,
      value: V,
      maybeTtl: Option[FiniteDuration]
    ): IO[Boolean] = delegate.put(key, value, maybeTtl)

    override def remove[K: KVEncoder[IO, *]](key: K): IO[Boolean] = delegate.remove(key)
  }

  "FallbackReconciler" should "send upserts for drift and removals for videos gone from the DB" in runIO {
    val video1 = SyncedVideo(scheduledVideoDownload("video-1"), List("user-1"))
    val video2 = SyncedVideo(scheduledVideoDownload("video-2"), List("user-1"))
    val inSync = ScheduledVideoUpserts.from(video1, capturedAt)

    for {
      dao <- StubFallbackSyncDao(video1, video2)
      transport <- RecordingTransport()
      coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
      _ <- coordination.markReconcileNeeded
      reconciler = new FallbackReconciler[IO, IO](
        manifestOf("video-1" -> ManifestEntry(inSync.hash, Instant.EPOCH), "gone" -> ManifestEntry("h", Instant.EPOCH)),
        dao,
        transport,
        coordination,
        "instance-a"
      )
      summary <- reconciler.reconcile
      sent <- transport.messages
      flagged <- coordination.isReconcileNeeded
    } yield {
      summary mustBe Some(ReconcileSummary(upserts = 1, removals = 1))
      sent.collect { case upsert: ScheduledVideoUpsert => upsert.videoId } mustBe List("video-2")
      sent.collect { case removal: ScheduledVideoRemoval => removal.videoId } mustBe List("gone")
      sent.collect { case removal: ScheduledVideoRemoval => removal.capturedAt } mustBe List(capturedAt)
      flagged mustBe false
    }
  }

  it should "not remove a video that the DB listing missed but that still exists" in runIO {
    val video1 = SyncedVideo(scheduledVideoDownload("video-1"), List("user-1"))

    for {
      dao <- StubFallbackSyncDao(video1)
      // Simulates a paging skip: findAll misses the video while findById still finds it.
      skippingDao = new FallbackSyncDao[IO] {
        override val currentTimestamp: IO[Instant] = IO.pure(capturedAt)
        override def findById(videoId: String): IO[Option[SyncedVideo]] = dao.findById(videoId)
        override val findAll: IO[List[SyncedVideo]] = IO.pure(Nil)
      }
      transport <- RecordingTransport()
      coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
      reconciler = new FallbackReconciler[IO, IO](
        manifestOf("video-1" -> ManifestEntry("stale", Instant.EPOCH)),
        skippingDao,
        transport,
        coordination,
        "instance-a"
      )
      _ <- reconciler.reconcile
      sent <- transport.messages
    } yield sent.collect { case removal: ScheduledVideoRemoval => removal } mustBe empty
  }

  it should "stamp messages with the database clock, taking a fresh timestamp for each removal re-check" in runIO {
    val video1 = SyncedVideo(scheduledVideoDownload("video-1"), List("user-1"))
    val listedAt = Instant.parse("2026-09-26T09:00:00.000001Z")
    val recheckedAt = Instant.parse("2026-09-26T09:00:05.000002Z")

    for {
      dao <- StubFallbackSyncDao(video1)
      _ <- dao.setTimestamps(listedAt, recheckedAt)
      transport <- RecordingTransport()
      coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
      reconciler = new FallbackReconciler[IO, IO](
        manifestOf("gone" -> ManifestEntry("h", Instant.EPOCH)),
        dao,
        transport,
        coordination,
        "instance-a"
      )
      _ <- reconciler.reconcile
      sent <- transport.messages
    } yield {
      sent.collect { case upsert: ScheduledVideoUpsert => upsert.capturedAt } mustBe List(listedAt)
      sent.collect { case removal: ScheduledVideoRemoval => removal } mustBe List(ScheduledVideoRemoval("gone", recheckedAt))
    }
  }

  it should "read the manifest before the database" in runIO {
    for {
      order <- Ref.of[IO, List[String]](Nil)
      manifestReader = new FallbackManifestReader[IO] {
        override val manifest: IO[Map[String, ManifestEntry]] = order.update(_ :+ "manifest").as(Map.empty)
      }
      dao = new FallbackSyncDao[IO] {
        override val currentTimestamp: IO[Instant] = IO.pure(capturedAt)
        override def findById(videoId: String): IO[Option[SyncedVideo]] = IO.pure(None)
        override val findAll: IO[List[SyncedVideo]] = order.update(_ :+ "database").as(Nil)
      }
      transport <- RecordingTransport()
      coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
      _ <- new FallbackReconciler[IO, IO](manifestReader, dao, transport, coordination, "instance-a").reconcile
      calls <- order.get
    } yield calls mustBe List("manifest", "database")
  }

  it should "skip when another instance holds the lock" in runIO {
    for {
      dao <- StubFallbackSyncDao()
      transport <- RecordingTransport()
      coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
      reconciler = new FallbackReconciler[IO, IO](manifestOf(), dao, transport, coordination, "instance-a")
      result <- coordination.withReconcileLock("instance-b")(reconciler.reconcile)
    } yield result mustBe Some(None)
  }

  it should "keep a flag raised mid-run instead of wiping it out when the run finishes" in runIO {
    // The flag is cleared right after the lock is acquired, before any read, precisely so that a flag raised for
    // a change made *during* this run (simulated here via the transport, standing in for the publisher) survives:
    // clearing it again at the end would wipe out that later, still-unsynced change.
    for {
      dao <- StubFallbackSyncDao()
      coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
      transport = new FallbackSyncTransport[IO] {
        override def send(messages: List[MainToFallbackMessage]): IO[Unit] = coordination.markReconcileNeeded
      }
      reconciler = new FallbackReconciler[IO, IO](manifestOf(), dao, transport, coordination, "instance-a")
      _ <- reconciler.reconcile
      flagged <- coordination.isReconcileNeeded
    } yield flagged mustBe true
  }

  it should "keep the schedule alive through key-value-store failures, run at startup, and react to a later flag" in {
    // Virtual time: flag checks land at 5, 10, 15, 20, 25 minutes. The first three hit the failing store.
    val test =
      for {
        remainingFailures <- Ref.of[IO, Int](3)
        coordination = new FallbackSyncCoordination[IO](
          new FlakyKeyValueStore(new InMemoryKeyValueStore[IO], remainingFailures)
        )
        manifestReads <- Ref.of[IO, Int](0)
        manifestReader = new FallbackManifestReader[IO] {
          override val manifest: IO[Map[String, ManifestEntry]] = manifestReads.update(_ + 1).as(Map.empty)
        }
        dao = new FallbackSyncDao[IO] {
          override val currentTimestamp: IO[Instant] = IO.pure(capturedAt)
          override def findById(videoId: String): IO[Option[SyncedVideo]] = IO.pure(None)
          override val findAll: IO[List[SyncedVideo]] = IO.pure(Nil)
        }
        transport <- RecordingTransport()
        reconciler = new FallbackReconciler[IO, IO](manifestReader, dao, transport, coordination, "instance-a")
        fiber <- reconciler.run(interval = 24.hours, flagCheckInterval = 5.minutes).compile.drain.start
        _ <- IO.sleep(1.minute)
        afterStartup <- manifestReads.get
        _ <- IO.sleep(20.minutes)
        failuresLeft <- remainingFailures.get
        beforeManualFlag <- manifestReads.get
        // Simulates the publisher raising the flag once the store is healthy again.
        _ <- coordination.markReconcileNeeded
        _ <- IO.sleep(5.minutes)
        afterManualFlag <- manifestReads.get
        _ <- fiber.cancel
      } yield {
        afterStartup mustBe 1 // the startup reconcile ran immediately, at t=0
        failuresLeft mustBe 0 // every failing flag check happened
        beforeManualFlag mustBe 1
        // Had the schedule died on a failing flag check, the flag raised afterwards could not trigger a reconcile
        afterManualFlag mustBe 2
      }

    runIO(TestControl.executeEmbed(test))
  }
}
