package com.ruchij.services.sync

import cats.effect.{Blocker, Sync}
import cats.implicits._
import com.ruchij.config.DownloadConfiguration
import com.ruchij.daos.resource.FileResourceDao
import com.ruchij.services.enrichment.SeekableByteChannelConverter
import com.ruchij.services.repository.FileRepositoryService.FileRepository
import com.ruchij.services.sync.SynchronizationServiceImpl.FileName
import com.ruchij.services.sync.models.SyncResult
import org.jcodec.api.FrameGrab

class SynchronizationServiceImpl[F[_]: Sync, A](
  fileRepositoryService: FileRepository[F, A],
  fileResourceDao: FileResourceDao[F],
  ioBlocker: Blocker,
  downloadConfiguration: DownloadConfiguration
)(implicit seekableByteChannelConverter: SeekableByteChannelConverter[F, A]) extends SynchronizationService[F] {

  override val sync: F[SyncResult] =
    fileRepositoryService
      .list(downloadConfiguration.videoFolder)
      .evalMap {
        case path @ FileName(fileName) => fileResourceDao.findByPath(fileName)
      }
      .compile
      .drain
      .as(SyncResult())


  def add(path: String) =
    for {
      backedType <- fileRepositoryService.backedType(path)
      seekableByteChannel <- seekableByteChannelConverter.convert(backedType)
      frameGrab = FrameGrab.createFrameGrab(seekableByteChannel)

      duration = frameGrab.getVideoTrack.getMeta.getTotalDuration
    }
    yield duration

}

object SynchronizationServiceImpl {
  object FileName {
    def unapply(path: String): Option[String] =
      path.split("[/\\\\]").lastOption
  }
}
