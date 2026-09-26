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
import com.ruchij.core.types.Clock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.time.Instant
import scala.concurrent.duration._

class FallbackReconcilerSpec extends AnyFlatSpec with Matchers {
  // Follows TestControl's virtual time, unlike the default Clock[IO]
  implicit val clock: Clock[IO] = new Clock[IO] {
    override val timestamp: IO[Instant] = IO.realTimeInstant
  }

  /** A reconciler whose manifest reads are counted, over an empty manifest and database. */
  private def countingReconciler(
    coordination: FallbackSyncCoordination[IO]
  ): IO[(FallbackReconciler[IO, IO], Ref[IO, Int])] =
    for {
      manifestReads <- Ref.of[IO, Int](0)
      manifestReader = new FallbackManifestReader[IO] {
        override val manifest: IO[Map[String, ManifestEntry]] = manifestReads.update(_ + 1).as(Map.empty)
      }
      dao <- StubFallbackSyncDao()
      transport <- RecordingTransport()
    } yield (new FallbackReconciler[IO, IO](manifestReader, dao, transport, coordination, "instance-a"), manifestReads)

  private def holdLock(keyValueStore: InMemoryKeyValueStore[IO], owner: String): IO[Unit] =
    keyValueStore.put[String, String](FallbackSyncCoordination.ReconcileLockKey, owner, Some(30.minutes)).void

  private def releaseLock(keyValueStore: InMemoryKeyValueStore[IO]): IO[Unit] =
    keyValueStore.remove[String](FallbackSyncCoordination.ReconcileLockKey).void
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
      summary <- reconciler.reconcile
      sent <- transport.messages
    } yield {
      sent.collect { case removal: ScheduledVideoRemoval => removal } mustBe empty
      // The re-check's upsert counts
      summary mustBe Some(ReconcileSummary(upserts = 1, removals = 0))
    }
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
      sent.collect { case removal: ScheduledVideoRemoval => removal } mustBe
        List(ScheduledVideoRemoval("gone", recheckedAt))
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

  it should "retry when the startup reconcile finds the lock held, and run once the lock is free" in {
    // A crashed instance's lock outlives it, and it already cleared the flag when it took the lock
    val test =
      for {
        keyValueStore <- IO.pure(new InMemoryKeyValueStore[IO])
        coordination = new FallbackSyncCoordination[IO](keyValueStore)
        (reconciler, manifestReads) <- countingReconciler(coordination)
        _ <- holdLock(keyValueStore, "crashed-instance")
        fiber <- reconciler.run(flagCheckInterval = 5.minutes).compile.drain.start
        _ <- IO.sleep(6.minutes)
        // Still locked at the first flag check, so the retry stays pending
        readsWhileLocked <- manifestReads.get
        _ <- releaseLock(keyValueStore)
        _ <- IO.sleep(5.minutes)
        readsAfterRelease <- manifestReads.get
        _ <- IO.sleep(10.minutes)
        readsLater <- manifestReads.get
        _ <- fiber.cancel
      } yield (readsWhileLocked, readsAfterRelease, readsLater)

    runIO(TestControl.executeEmbed(test).map(_ mustBe ((0, 1, 1))))
  }

  it should "not rerun after a simultaneous startup once the lock's holder completes a reconcile" in {
    // Every instance starts at once after a deploy, and only one of them takes the lock
    val test =
      for {
        keyValueStore <- IO.pure(new InMemoryKeyValueStore[IO])
        coordination = new FallbackSyncCoordination[IO](keyValueStore)
        (reconciler, manifestReads) <- countingReconciler(coordination)
        _ <- holdLock(keyValueStore, "instance-b")
        fiber <- reconciler.run(flagCheckInterval = 5.minutes).compile.drain.start
        _ <- IO.sleep(1.minute)
        // instance-b completes its startup reconcile
        completedAt <- IO.realTimeInstant
        _ <- coordination.recordSuccessfulReconcile(completedAt)
        _ <- releaseLock(keyValueStore)
        _ <- IO.sleep(20.minutes)
        reads <- manifestReads.get
        flagged <- coordination.isReconcileNeeded
        _ <- fiber.cancel
      } yield (reads, flagged)

    runIO(TestControl.executeEmbed(test).map(_ mustBe ((0, false))))
  }

  it should "still run a flagged reconcile after the lock's holder completes one that began before the flag" in {
    val test =
      for {
        keyValueStore <- IO.pure(new InMemoryKeyValueStore[IO])
        coordination = new FallbackSyncCoordination[IO](keyValueStore)
        (reconciler, manifestReads) <- countingReconciler(coordination)
        fiber <- reconciler.run(flagCheckInterval = 5.minutes).compile.drain.start
        _ <- IO.sleep(1.minute)
        // instance-b takes the lock and clears the flag, then a change is flagged while it runs
        _ <- holdLock(keyValueStore, "instance-b")
        _ <- coordination.markReconcileNeeded
        _ <- IO.sleep(5.minutes)
        readsWhileLocked <- manifestReads.get
        completedAt <- IO.realTimeInstant
        _ <- coordination.recordSuccessfulReconcile(completedAt)
        _ <- releaseLock(keyValueStore)
        _ <- IO.sleep(5.minutes)
        reads <- manifestReads.get
        _ <- fiber.cancel
      } yield (readsWhileLocked, reads)

    runIO(TestControl.executeEmbed(test).map(_ mustBe ((1, 2))))
  }

  it should "not flag a retry when the daily reconcile finds the lock held" in {
    val test =
      for {
        keyValueStore <- IO.pure(new InMemoryKeyValueStore[IO])
        coordination = new FallbackSyncCoordination[IO](keyValueStore)
        (reconciler, manifestReads) <- countingReconciler(coordination)
        fiber <- reconciler.run(interval = 24.hours, flagCheckInterval = 5.minutes).compile.drain.start
        _ <- IO.sleep(1.hour)
        _ <- holdLock(keyValueStore, "instance-b")
        _ <- IO.sleep(23.hours + 1.minute)
        flagged <- coordination.isReconcileNeeded
        reads <- manifestReads.get
        _ <- fiber.cancel
      } yield (flagged, reads)

    runIO(TestControl.executeEmbed(test).map(_ mustBe ((false, 1))))
  }

  it should "skip the daily reconcile when any instance completed one within the last 20 hours" in {
    val test =
      for {
        coordination <- IO.pure(new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO]))
        (reconciler, manifestReads) <- countingReconciler(coordination)
        fiber <- reconciler.run(interval = 24.hours, flagCheckInterval = 5.minutes).compile.drain.start
        _ <- IO.sleep(23.hours)
        // Another instance's startup reconcile
        otherInstanceRun <- IO.realTimeInstant
        _ <- coordination.recordSuccessfulReconcile(otherInstanceRun)
        _ <- IO.sleep(1.hour + 1.minute)
        afterSkippedDailyRun <- manifestReads.get
        _ <- IO.sleep(24.hours)
        afterNextDailyRun <- manifestReads.get
        lastRun <- coordination.lastSuccessfulReconcile
        _ <- fiber.cancel
      } yield (afterSkippedDailyRun, afterNextDailyRun, lastRun)

    runIO {
      TestControl.executeEmbed(test).map {
        case (afterSkippedDailyRun, afterNextDailyRun, lastRun) =>
          afterSkippedDailyRun mustBe 1 // only the startup reconcile
          afterNextDailyRun mustBe 2 // 25 hours after the other instance's run
          lastRun mustBe Some(Instant.EPOCH.plusSeconds(48.hours.toSeconds))
      }
    }
  }

  private def massRemovalRun(
    databaseVideos: List[SyncedVideo],
    manifestEntries: Map[String, ManifestEntry],
    allowMassRemoval: Boolean = false
  ): IO[(Option[ReconcileSummary], List[MainToFallbackMessage])] =
    for {
      dao <- StubFallbackSyncDao(databaseVideos: _*)
      transport <- RecordingTransport()
      coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])
      reconciler = new FallbackReconciler[IO, IO](
        new FallbackManifestReader[IO] {
          override val manifest: IO[Map[String, ManifestEntry]] = IO.pure(manifestEntries)
        },
        dao,
        transport,
        coordination,
        "instance-a",
        allowMassRemoval
      )
      summary <- reconciler.reconcile
      sent <- transport.messages
    } yield (summary, sent)

  private def goneEntries(count: Int): Map[String, ManifestEntry] =
    (1 to count).map(index => s"gone-$index" -> ManifestEntry("h", Instant.EPOCH)).toMap

  it should "withhold every removal when the database returns no videos but the manifest isn't empty" in runIO {
    massRemovalRun(Nil, goneEntries(3)).map {
      case (summary, sent) =>
        sent mustBe empty
        summary mustBe Some(ReconcileSummary(upserts = 0, removals = 0, withheldRemovals = 3))
    }
  }

  it should "withhold removals beyond max(50, 20% of the manifest) but still send upserts" in runIO {
    val video = SyncedVideo(scheduledVideoDownload("video-1"), List("user-1"))

    for {
      (withheldSummary, withheldSent) <- massRemovalRun(List(video), goneEntries(51))
      (allowedSummary, allowedSent) <- massRemovalRun(List(video), goneEntries(50))
      // Past 50, the limit is 20% of the manifest: 190 of 950, then 210 of 1,050
      (largeAllowed, _) <- massRemovalRun(manyVideos(800), manifestEntriesOf(manyVideos(800)) ++ goneEntries(150))
      (largeWithheld, _) <- massRemovalRun(manyVideos(800), manifestEntriesOf(manyVideos(800)) ++ goneEntries(250))
    } yield {
      withheldSent.collect { case upsert: ScheduledVideoUpsert => upsert.videoId } mustBe List("video-1")
      withheldSent.collect { case removal: ScheduledVideoRemoval => removal } mustBe empty
      withheldSummary mustBe Some(ReconcileSummary(upserts = 1, removals = 0, withheldRemovals = 51))

      allowedSent.collect { case removal: ScheduledVideoRemoval => removal } must have size 50
      allowedSummary mustBe Some(ReconcileSummary(upserts = 1, removals = 50))

      largeAllowed.map(_.removals) mustBe Some(150)
      largeWithheld.map(_.withheldRemovals) mustBe Some(250)
    }
  }

  private def manyVideos(count: Int): List[SyncedVideo] =
    (1 to count).toList.map(index => SyncedVideo(scheduledVideoDownload(s"video-$index"), List("user-1")))

  private def manifestEntriesOf(videos: List[SyncedVideo]): Map[String, ManifestEntry] =
    videos.map { video =>
      val upsert = ScheduledVideoUpserts.from(video, capturedAt)
      upsert.videoId -> ManifestEntry(upsert.hash, capturedAt)
    }.toMap

  it should "send a mass removal when explicitly allowed" in runIO {
    massRemovalRun(Nil, goneEntries(60), allowMassRemoval = true).map {
      case (summary, sent) =>
        sent.collect { case removal: ScheduledVideoRemoval => removal } must have size 60
        summary mustBe Some(ReconcileSummary(upserts = 0, removals = 60))
    }
  }

  "FallbackReconciler.maxRemovals" should "allow 50 removals, or 20% of a larger manifest" in {
    FallbackReconciler.maxRemovals(0) mustBe 50
    FallbackReconciler.maxRemovals(250) mustBe 50
    FallbackReconciler.maxRemovals(1000) mustBe 200
  }

  "FallbackReconciler.entriesAhead" should "list manifest entries stamped more than a minute ahead of the database" in {
    val databaseTime = Instant.parse("2026-09-26T08:00:00Z")
    val manifest =
      Map(
        "behind" -> ManifestEntry("h", databaseTime.minusSeconds(3600)),
        "slightly-ahead" -> ManifestEntry("h", databaseTime.plusSeconds(60)),
        "ahead" -> ManifestEntry("h", databaseTime.plusSeconds(61))
      )

    FallbackReconciler.entriesAhead(manifest, databaseTime) mustBe List("ahead")
  }
}
