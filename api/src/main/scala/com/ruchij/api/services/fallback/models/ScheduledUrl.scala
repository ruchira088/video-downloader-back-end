package com.ruchij.api.services.fallback.models

import org.http4s.Uri
import org.joda.time.DateTime

final case class ScheduledUrl(id: String, createdAt: DateTime, userId: String, url: Uri, status: SchedulingStatus)