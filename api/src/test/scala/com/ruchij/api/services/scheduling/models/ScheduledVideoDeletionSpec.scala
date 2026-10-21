package com.ruchij.api.services.scheduling.models

import com.ruchij.api.services.scheduling.models.ScheduledVideoDeletion.ScheduledVideoDeletionTopic
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.test.data.CoreTestData
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class ScheduledVideoDeletionSpec extends AnyFlatSpec with Matchers {

  private val deletion =
    ScheduledVideoDeletion(
      ScheduledVideoDownload(
        CoreTestData.Timestamp,
        CoreTestData.Timestamp,
        SchedulingStatus.Deleted,
        0,
        CoreTestData.YouTubeVideo.videoMetadata,
        None,
        None
      ),
      List("user-1", "user-2")
    )

  "ScheduledVideoDeletionTopic" should "round trip a deletion through its JSON codec" in {
    ScheduledVideoDeletionTopic.jsonCodec.decodeJson(ScheduledVideoDeletionTopic.jsonCodec(deletion)) mustBe
      Right(deletion)
  }

  it should "round trip a deletion through its Avro codec" in {
    val codec = ScheduledVideoDeletionTopic.avroCodec

    codec.encode(deletion).flatMap(encoded => codec.schema.flatMap(schema => codec.decode(encoded, schema))) mustBe
      Right(deletion)
  }
}
