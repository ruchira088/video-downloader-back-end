package com.ruchij.batch.daos.filesync.models

import org.joda.time.DateTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class FileSyncSpec extends AnyFlatSpec with Matchers {

  private val timestamp = new DateTime(2024, 5, 15, 10, 30)
  private val syncedTimestamp = new DateTime(2024, 5, 15, 10, 35)

  "FileSync" should "store lockedAt and path with no syncedAt" in {
    val fileSync = FileSync(timestamp, "/videos/video.mp4", None)

    fileSync.lockedAt mustBe timestamp
    fileSync.path mustBe "/videos/video.mp4"
    fileSync.syncedAt mustBe None
  }

  it should "store lockedAt, path, and syncedAt" in {
    val fileSync = FileSync(timestamp, "/videos/video.mp4", Some(syncedTimestamp))

    fileSync.lockedAt mustBe timestamp
    fileSync.path mustBe "/videos/video.mp4"
    fileSync.syncedAt mustBe Some(syncedTimestamp)
  }

  it should "support equality" in {
    val fileSync1 = FileSync(timestamp, "/videos/video.mp4", None)
    val fileSync2 = FileSync(timestamp, "/videos/video.mp4", None)

    fileSync1 mustBe fileSync2
  }

  it should "not be equal with different paths" in {
    val fileSync1 = FileSync(timestamp, "/videos/video1.mp4", None)
    val fileSync2 = FileSync(timestamp, "/videos/video2.mp4", None)

    fileSync1 must not be fileSync2
  }

  it should "support copy" in {
    val fileSync = FileSync(timestamp, "/videos/video.mp4", None)
    val completed = fileSync.copy(syncedAt = Some(syncedTimestamp))

    completed.lockedAt mustBe timestamp
    completed.path mustBe "/videos/video.mp4"
    completed.syncedAt mustBe Some(syncedTimestamp)
  }

  it should "indicate incomplete sync when syncedAt is None" in {
    val fileSync = FileSync(timestamp, "/videos/video.mp4", None)

    fileSync.syncedAt.isEmpty mustBe true
  }

  it should "indicate complete sync when syncedAt is defined" in {
    val fileSync = FileSync(timestamp, "/videos/video.mp4", Some(syncedTimestamp))

    fileSync.syncedAt.isDefined mustBe true
  }
}
