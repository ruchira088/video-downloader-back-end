package com.ruchij.core.daos.hash.models

import com.ruchij.core.types.TimeUtils
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps

class VideoPerceptualHashSpec extends AnyFlatSpec with Matchers {

  private val timestamp = TimeUtils.instantOf(2024, 5, 15, 10, 30)

  "VideoPerceptualHash" should "hold all fields correctly" in {
    val hash = VideoPerceptualHash("video-1", timestamp, 5 minutes, BigInt(123456789L), 150 seconds)

    hash.videoId mustBe "video-1"
    hash.createdAt mustBe timestamp
    hash.duration mustBe (5 minutes)
    hash.snapshotPerceptualHash mustBe BigInt(123456789L)
    hash.snapshotTimestamp mustBe (150 seconds)
  }

  it should "support equality" in {
    val hash1 = VideoPerceptualHash("video-1", timestamp, 5 minutes, BigInt(100L), 150 seconds)
    val hash2 = VideoPerceptualHash("video-1", timestamp, 5 minutes, BigInt(100L), 150 seconds)
    val hash3 = VideoPerceptualHash("video-1", timestamp, 5 minutes, BigInt(200L), 150 seconds)

    hash1 mustBe hash2
    hash1 must not be hash3
  }

  it should "support copy" in {
    val hash = VideoPerceptualHash("video-1", timestamp, 5 minutes, BigInt(100L), 150 seconds)
    val updated = hash.copy(snapshotPerceptualHash = BigInt(999L))

    updated.videoId mustBe "video-1"
    updated.snapshotPerceptualHash mustBe BigInt(999L)
  }

  it should "handle large BigInt hash values" in {
    val largeHash = BigInt("123456789012345678901234567890")
    val hash = VideoPerceptualHash("video-1", timestamp, 5 minutes, largeHash, 150 seconds)

    hash.snapshotPerceptualHash mustBe largeHash
  }

  it should "support pattern matching" in {
    val hash = VideoPerceptualHash("video-1", timestamp, 5 minutes, BigInt(42L), 150 seconds)

    hash match {
      case VideoPerceptualHash(vid, _, dur, h, st) =>
        vid mustBe "video-1"
        dur mustBe (5 minutes)
        h mustBe BigInt(42L)
        st mustBe (150 seconds)
    }
  }
}
