package com.ruchij.api.daos.playlist.models

import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.video.models.Video
import java.time.Instant

final case class Playlist(
  id: String,
  userId: String,
  createdAt: Instant,
  title: String,
  description: Option[String],
  videos: Seq[Video],
  albumArt: Option[FileResource]
)
