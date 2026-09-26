package com.ruchij.api.services.fallback

import com.ruchij.api.services.fallback.FallbackSyncTestData.fixtureUpsert
import com.ruchij.api.services.fallback.aws.ManifestEntry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.time.Instant

class ReconcileDiffSpec extends AnyFlatSpec with Matchers {
  private def upsert(videoId: String, hash: String) = fixtureUpsert.copy(videoId = videoId, hash = hash)
  private def entry(hash: String) = ManifestEntry(hash, Instant.EPOCH)

  "ReconcileDiff" should "upsert missing and changed videos, remove extra ones and skip matching ones" in {
    val diff =
      ReconcileDiff.compute(
        manifest = Map("same" -> entry("h1"), "changed" -> entry("old"), "extra" -> entry("h3")),
        current = List(upsert("same", "h1"), upsert("changed", "new"), upsert("missing", "h4"))
      )

    diff.upserts.map(_.videoId).sorted mustBe List("changed", "missing")
    diff.removedVideoIds mustBe List("extra")
  }

  it should "do nothing when both sides match" in {
    ReconcileDiff.compute(Map("a" -> entry("h")), List(upsert("a", "h"))) mustBe ReconcileDiff(Nil, Nil)
  }

  it should "upsert a malformed manifest item that still exists and remove one that doesn't" in {
    val malformed = ManifestEntry(ManifestEntry.MalformedHash, Instant.EPOCH)
    val diff =
      ReconcileDiff.compute(
        manifest = Map("exists" -> malformed, "gone" -> malformed),
        current = List(upsert("exists", "h1"))
      )

    diff mustBe ReconcileDiff(List(upsert("exists", "h1")), List("gone"))
  }
}
