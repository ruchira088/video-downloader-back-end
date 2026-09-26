package com.ruchij.api.services.fallback

import com.ruchij.api.services.fallback.aws.ManifestEntry
import com.ruchij.api.services.fallback.models.ScheduledVideoUpsert

final case class ReconcileDiff(upserts: List[ScheduledVideoUpsert], removedVideoIds: List[String])

object ReconcileDiff {
  def compute(manifest: Map[String, ManifestEntry], current: List[ScheduledVideoUpsert]): ReconcileDiff = {
    val upserts = current.filterNot(upsert => manifest.get(upsert.videoId).map(_.hash).contains(upsert.hash))
    val currentVideoIds = current.map(_.videoId).toSet

    ReconcileDiff(upserts, manifest.keys.filterNot(currentVideoIds.contains).toList.sorted)
  }
}
