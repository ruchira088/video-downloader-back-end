package com.ruchij.core.daos.resource.models

import org.http4s.MediaType
import org.joda.time.DateTime

final case class FileResource(id: String, createdAt: DateTime, path: String, mediaType: MediaType, size: Long)
