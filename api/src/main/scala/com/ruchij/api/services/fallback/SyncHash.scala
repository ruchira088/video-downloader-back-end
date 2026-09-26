package com.ruchij.api.services.fallback

import com.ruchij.api.services.fallback.models.{ScheduledVideoUpsert, SyncJson}
import io.circe.Json

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object SyncHash {
  // Computed only on the main side; the fallback stores it as an opaque string and never recomputes it.
  def of(upsert: ScheduledVideoUpsert): String = {
    val canonical =
      Json
        .arr(
          Json.fromString(upsert.videoId),
          Json.fromString(upsert.url),
          Json.fromString(upsert.videoSite),
          Json.fromString(upsert.title),
          Json.fromLong(upsert.durationMs),
          Json.fromLong(upsert.sizeBytes),
          Json.fromString(upsert.status),
          Json.fromString(SyncJson.formatTimestamp(upsert.scheduledAt)),
          upsert.completedAt.fold(Json.Null)(instant => Json.fromString(SyncJson.formatTimestamp(instant))),
          Json.arr(upsert.userIds.sorted.map(Json.fromString): _*)
        )
        .noSpaces

    MessageDigest
      .getInstance("SHA-256")
      .digest(canonical.getBytes(StandardCharsets.UTF_8))
      .take(8)
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
  }
}
