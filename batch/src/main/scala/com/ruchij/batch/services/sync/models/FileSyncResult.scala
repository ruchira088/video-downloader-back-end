package com.ruchij.batch.services.sync.models

import com.ruchij.core.daos.video.models.Video

sealed trait FileSyncResult {
  val filePath: String
}

object FileSyncResult {
  final case class ExistingVideo(filePath: String) extends FileSyncResult

  final case class VideoSynced(video: Video) extends FileSyncResult {
    override val filePath: String = video.fileResource.path
  }

  final case class MissingVideoFile(video: Video) extends FileSyncResult {
    override val filePath: String = video.fileResource.path
  }

  final case class SyncError(throwable: Throwable, filePath: String) extends FileSyncResult

  final case class IgnoredFile(filePath: String) extends FileSyncResult
}
