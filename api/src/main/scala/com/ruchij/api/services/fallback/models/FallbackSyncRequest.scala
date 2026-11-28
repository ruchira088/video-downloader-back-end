package com.ruchij.api.services.fallback.models

import com.ruchij.core.messaging.MessagingTopic
import io.circe.generic.semiauto.deriveCodec
import vulcan.Codec
import vulcan.generic.*

final case class FallbackSyncRequest(videoId: String)

object FallbackSyncRequest {
  given FallbackSyncRequestTopic: MessagingTopic[FallbackSyncRequest] {
    override val name: String = "fallback-sync-requests"

    override val avroCodec: Codec[FallbackSyncRequest] = Codec.derive[FallbackSyncRequest]

    override val jsonCodec: io.circe.Codec[FallbackSyncRequest] = deriveCodec[FallbackSyncRequest]
  }
}
