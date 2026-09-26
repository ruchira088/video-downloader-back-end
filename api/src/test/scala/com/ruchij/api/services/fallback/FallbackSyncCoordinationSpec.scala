package com.ruchij.api.services.fallback

import cats.effect.IO
import com.ruchij.core.kv.InMemoryKeyValueStore
import com.ruchij.core.test.IOSupport.runIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class FallbackSyncCoordinationSpec extends AnyFlatSpec with Matchers {

  "FallbackSyncCoordination" should "set, read and clear the reconcile flag" in runIO {
    val coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])

    for {
      before <- coordination.isReconcileNeeded
      _ <- coordination.markReconcileNeeded
      marked <- coordination.isReconcileNeeded
      _ <- coordination.clearReconcileNeeded
      cleared <- coordination.isReconcileNeeded
    } yield (before, marked, cleared) mustBe ((false, true, false))
  }

  it should "let only one owner hold the reconcile lock and release it afterwards" in runIO {
    val coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])

    for {
      nested <- coordination.withReconcileLock("instance-a") {
        coordination.withReconcileLock("instance-b")(IO.pure("b ran"))
      }
      afterRelease <- coordination.withReconcileLock("instance-b")(IO.pure("b ran"))
    } yield {
      nested mustBe Some(None)
      afterRelease mustBe Some("b ran")
    }
  }

  it should "release the lock when the work fails" in runIO {
    val coordination = new FallbackSyncCoordination[IO](new InMemoryKeyValueStore[IO])

    for {
      failed <- coordination.withReconcileLock("instance-a")(IO.raiseError[Unit](new RuntimeException)).attempt
      next <- coordination.withReconcileLock("instance-b")(IO.pure(1))
    } yield {
      failed.isLeft mustBe true
      next mustBe Some(1)
    }
  }
}
