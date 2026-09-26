package com.ruchij.api.services.fallback

import cats.effect.IO
import cats.effect.kernel.Ref
import com.ruchij.api.services.fallback.FallbackSyncStubs._
import com.ruchij.api.services.fallback.FallbackSyncTestData.{capturedAt, scheduledVideoDownload}
import com.ruchij.api.services.fallback.aws.{FallbackManifestReader, ManifestEntry}
import com.ruchij.api.services.fallback.models.{ScheduledVideoRemoval, ScheduledVideoUpsert}
import com.ruchij.core.kv.InMemoryKeyValueStore
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.Providers
import com.ruchij.core.types.Clock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.time.Instant

class FallbackReconcilerSpec extends AnyFlatSpec with Matchers {
  implicit val clock: Clock[IO] = Providers.stubClock[IO](capturedAt)

  private def manifestOf(entries: (String, ManifestEntry)*): FallbackManifestReader[IO] =
    new FallbackManifestReader[IO] {
      override val manifest: IO[Map[String, ManifestEntry]] = IO.pure(entries.toMap)
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
      flagged mustBe false
    }
  }

  it should "not remove a video that the DB listing missed but that still exists" in runIO {
    val video1 = SyncedVideo(scheduledVideoDownload("video-1"), List("user-1"))

    for {
      dao <- StubFallbackSyncDao(video1)
      // Simulates a paging skip: findAll misses the video while findById still finds it.
      skippingDao = new FallbackSyncDao[IO] {
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

  it should "read the manifest before the database" in runIO {
    for {
      order <- Ref.of[IO, List[String]](Nil)
      manifestReader = new FallbackManifestReader[IO] {
        override val manifest: IO[Map[String, ManifestEntry]] = order.update(_ :+ "manifest").as(Map.empty)
      }
      dao = new FallbackSyncDao[IO] {
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
}
