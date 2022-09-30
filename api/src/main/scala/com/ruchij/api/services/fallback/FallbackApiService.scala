package com.ruchij.api.services.fallback

import com.ruchij.api.services.fallback.models.{ScheduledUrl, UserInformation}
import fs2.Stream

trait FallbackApiService[F[_]] {
  val scheduledUrls: Stream[F, ScheduledUrl]

  def commit(scheduledUrlId: String): F[ScheduledUrl]

  def userInformation(userId: String): F[UserInformation]
}
