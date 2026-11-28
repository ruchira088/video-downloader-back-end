package com.ruchij.api.services

import com.ruchij.api.services.authentication.AuthenticationService.Secret
import com.ruchij.api.services.authentication.models.AuthenticationToken
import com.ruchij.api.services.authentication.models.AuthenticationToken.AuthenticationTokenKey
import com.ruchij.api.services.fallback.models.{FallbackSyncRequest, ResolvedRequestKey}
import com.ruchij.api.services.health.models.kv.HealthCheckKey
import com.ruchij.api.services.health.models.messaging.HealthCheckMessage
import com.ruchij.api.services.scheduling.models.ScheduledVideoDeletion
import com.ruchij.core.formats.{CoreGoldenFormatSpec, GoldenFormat, GoldenFormatSpec}
import com.ruchij.core.types.TimeUtils

// Add every new MessagingTopic defined in api here, so that its JSON format is pinned too, and likewise every key and
// value type stored in the key-value store
class ApiGoldenFormatSpec extends GoldenFormatSpec("api", ApiGoldenFormatSpec.formats)

object ApiGoldenFormatSpec {
  private val timestamp = TimeUtils.instantOf(2024, 3, 10, 8, 15)

  private val secret = Secret("4f7c2a90-5e1b-4d3a-9c8e-0b6a1f2d3e4c")

  val formats: List[GoldenFormat] =
    List(
      GoldenFormat.json(
        ScheduledVideoDeletion(CoreGoldenFormatSpec.scheduledVideoDownload, List("user-id-1", "user-id-2"))
      ),
      GoldenFormat.json(HealthCheckMessage("instance-id", timestamp)),
      GoldenFormat.json(FallbackSyncRequest("video-id")),
      GoldenFormat.kvKey("authentication-token", AuthenticationTokenKey(secret)),
      GoldenFormat.kvValue(
        "authentication-token",
        AuthenticationToken("user-id", secret, timestamp.plusSeconds(3600), timestamp, 3, timestamp.minusSeconds(60))
      ),
      GoldenFormat.kvKey("health-check", HealthCheckKey(timestamp)),
      GoldenFormat.kvKey("fallback-sync-resolved-request", ResolvedRequestKey("request-id"))
    )
}
