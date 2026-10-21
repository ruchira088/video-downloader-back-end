package com.ruchij.api.services.scheduling.models

import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.messaging.MessagingTopic
import com.ruchij.core.messaging.MessagingTopic.ScheduledVideoDownloadTopic
import io.circe.generic.semiauto.deriveCodec
import vulcan.Codec
import vulcan.generic._

/**
  * An admin's delete of a scheduled video download, together with the users who had scheduled it.
  *
  * Their permissions are deleted with the download, so the users are carried in the message: the API instances use
  * them to send the Deleted update to those users' event streams, which could no longer find a permission to check.
  */
final case class ScheduledVideoDeletion(scheduledVideoDownload: ScheduledVideoDownload, userIds: List[String])

object ScheduledVideoDeletion {
  implicit case object ScheduledVideoDeletionTopic extends MessagingTopic[ScheduledVideoDeletion] {
    override val name: String = "scheduled-video-deletions"

    override val avroCodec: Codec[ScheduledVideoDeletion] = {
      implicit val scheduledVideoDownloadCodec: Codec[ScheduledVideoDownload] = ScheduledVideoDownloadTopic.avroCodec

      Codec.derive[ScheduledVideoDeletion]
    }

    override val jsonCodec: io.circe.Codec[ScheduledVideoDeletion] = {
      implicit val scheduledVideoDownloadCodec: io.circe.Codec[ScheduledVideoDownload] =
        ScheduledVideoDownloadTopic.jsonCodec

      deriveCodec[ScheduledVideoDeletion]
    }
  }
}
