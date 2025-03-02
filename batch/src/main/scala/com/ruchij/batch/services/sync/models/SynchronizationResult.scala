package com.ruchij.batch.services.sync.models

import FileSyncResult.{ExistingVideo, IgnoredFile, MissingVideoFile, SyncError, VideoSnapshotsCreated, VideoSynced}

final case class SynchronizationResult(
  existingVideoFiles: Long,
  syncedVideos: Long,
  missingVideoFiles: Long,
  videoCountOfSnapshotsUpdated: Long,
  syncErrors: Long,
  ignoredFiles: Long
) {
  self =>

  val + : FileSyncResult => SynchronizationResult = {
    case _: VideoSnapshotsCreated => self.copy(videoCountOfSnapshotsUpdated + 1)
    case _: VideoSynced => self.copy(syncedVideos = syncedVideos + 1)
    case _: IgnoredFile => self.copy(ignoredFiles = ignoredFiles + 1)
    case _: ExistingVideo => self.copy(existingVideoFiles = existingVideoFiles + 1)
    case _: MissingVideoFile => self.copy(missingVideoFiles = missingVideoFiles + 1)
    case _: SyncError => self.copy(syncErrors = syncErrors + 1)
  }

  def +(synchronizationResult: SynchronizationResult): SynchronizationResult =
    SynchronizationResult(
      existingVideoFiles + synchronizationResult.existingVideoFiles,
      syncedVideos + synchronizationResult.syncedVideos,
      missingVideoFiles + synchronizationResult.missingVideoFiles,
      videoCountOfSnapshotsUpdated + synchronizationResult.videoCountOfSnapshotsUpdated,
      syncErrors + synchronizationResult.syncErrors,
      ignoredFiles + synchronizationResult.ignoredFiles
    )

  def prettyPrint: String =
    s"Synchronization Result = Existing: $existingVideoFiles, Synced: $syncedVideos, Errors: $syncErrors, Ignored: $ignoredFiles, Deleted: $missingVideoFiles"

}

object SynchronizationResult {
  val Zero: SynchronizationResult = SynchronizationResult(0, 0, 0, 0, 0, 0)
}
