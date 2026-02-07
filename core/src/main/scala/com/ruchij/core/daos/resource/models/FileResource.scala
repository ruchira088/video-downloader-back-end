package com.ruchij.core.daos.resource.models

import org.http4s.MediaType
import java.time.Instant

final case class FileResource(id: String, createdAt: Instant, path: String, mediaType: MediaType, size: Long)
