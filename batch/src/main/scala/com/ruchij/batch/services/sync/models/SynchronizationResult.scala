package com.ruchij.batch.services.sync.models

import FileSyncResult.{ExistingVideo, IgnoredFile, SyncError, VideoSynced}

case class SynchronizationResult(existingVideoFiles: Long, syncedVideos: Long, syncErrors: Long, ignoredFiles: Long) {
  self =>

  val + : FileSyncResult => SynchronizationResult = {
    case _: VideoSynced => self.copy(syncedVideos = syncedVideos + 1)
    case _: IgnoredFile => self.copy(ignoredFiles = ignoredFiles + 1)
    case _: ExistingVideo => self.copy(existingVideoFiles = existingVideoFiles + 1)
    case _: SyncError => self.copy(syncErrors = syncErrors + 1)
  }

  def prettyPrint: String =
    s"Synchronization Result = Existing: $existingVideoFiles, Synced: $syncedVideos, Errors: $syncErrors, Ignored: $ignoredFiles"

}

object SynchronizationResult {
  val zero: SynchronizationResult = SynchronizationResult(0, 0, 0, 0)
}
