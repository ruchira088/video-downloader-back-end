package com.ruchij.core.daos.duplicate.models

import com.ruchij.core.types.TimeUtils
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class DuplicateVideoSpec extends AnyFlatSpec with Matchers {

  private val timestamp = TimeUtils.instantOf(2024, 5, 15, 10, 30)

  "DuplicateVideo" should "hold videoId, duplicateGroupId, and createdAt" in {
    val dv = DuplicateVideo("video-1", "group-a", timestamp)

    dv.videoId mustBe "video-1"
    dv.duplicateGroupId mustBe "group-a"
    dv.createdAt mustBe timestamp
  }

  it should "support equality" in {
    val dv1 = DuplicateVideo("video-1", "group-a", timestamp)
    val dv2 = DuplicateVideo("video-1", "group-a", timestamp)
    val dv3 = DuplicateVideo("video-2", "group-a", timestamp)

    dv1 mustBe dv2
    dv1 must not be dv3
  }

  it should "support copy" in {
    val dv = DuplicateVideo("video-1", "group-a", timestamp)
    val updated = dv.copy(duplicateGroupId = "group-b")

    updated.videoId mustBe "video-1"
    updated.duplicateGroupId mustBe "group-b"
  }

  it should "support pattern matching" in {
    val dv = DuplicateVideo("video-1", "group-a", timestamp)

    dv match {
      case DuplicateVideo(vid, gid, _) =>
        vid mustBe "video-1"
        gid mustBe "group-a"
    }
  }
}
