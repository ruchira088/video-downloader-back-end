package com.ruchij.api.services.fallback.models

import java.time.Instant

sealed trait MainToFallbackMessage

final case class ScheduledVideoUpsert(
  videoId: String,
  capturedAt: Instant,
  hash: String,
  userIds: List[String],
  url: String,
  videoSite: String,
  title: String,
  durationMs: Long,
  sizeBytes: Long,
  status: String,
  scheduledAt: Instant,
  completedAt: Option[Instant]
) extends MainToFallbackMessage

final case class ScheduledVideoRemoval(videoId: String, capturedAt: Instant) extends MainToFallbackMessage

final case class RequestResolved(requestId: String, userId: String, outcome: ResolutionOutcome)
    extends MainToFallbackMessage

/** The user's password was reset, or the user deleted, at `capturedAt`: the fallback deletes their account (named after
  * `email`), so the old password and tokens stop working there until they sign up to the fallback again. */
final case class UserRevocation(userId: String, email: String, capturedAt: Instant) extends MainToFallbackMessage

sealed trait ResolutionOutcome

object ResolutionOutcome {
  final case class Scheduled(upsert: ScheduledVideoUpsert) extends ResolutionOutcome

  final case class Rejected(reason: String) extends ResolutionOutcome
}

final case class ScheduleRequest(requestId: String, userId: String, url: String, requestedAt: Instant)
