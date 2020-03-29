package com.ruchij.daos.video.models

import java.nio.file.Path

import com.ruchij.daos.videometadata.models.VideoMetadata
import org.joda.time.DateTime

case class Video(downloadedAt: DateTime, videoMetadata: VideoMetadata, path: Path)
