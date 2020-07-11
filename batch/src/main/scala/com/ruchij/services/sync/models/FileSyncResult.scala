package com.ruchij.services.sync.models

import com.ruchij.daos.video.models.Video

sealed trait FileSyncResult {
  val filePath: String
}

object FileSyncResult {
  case class ExistingVideo(filePath: String) extends FileSyncResult

  case class VideoSynced(video: Video) extends FileSyncResult {
    override val filePath: String = video.fileResource.path
  }

  case class SyncError(throwable: Throwable, filePath: String) extends FileSyncResult

  case class IgnoredFile(filePath: String) extends FileSyncResult
}
