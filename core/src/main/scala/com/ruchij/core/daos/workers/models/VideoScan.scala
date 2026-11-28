package com.ruchij.core.daos.workers.models

import cats.{Monad, MonadThrow}
import com.ruchij.core.daos.workers.models.VideoScan.ScanStatus
import com.ruchij.core.kv.codecs.{KVDecoder, KVEncoder}
import enumeratum.{Enum, EnumEntry}
import java.time.Instant

final case class VideoScan(updatedAt: Instant, status: ScanStatus)

object VideoScan {
  sealed trait ScanStatus extends EnumEntry

  object ScanStatus extends Enum[ScanStatus] {
    case object Idle extends ScanStatus
    case object Scheduled extends ScanStatus
    case object InProgress extends ScanStatus
    case object Error extends ScanStatus

    override def values: IndexedSeq[ScanStatus] = findValues
  }

  implicit def kvEncoder[F[_]: Monad]: KVEncoder[F, VideoScan] =
    KVEncoder.forProduct2((videoScan: VideoScan) => (videoScan.updatedAt, videoScan.status))

  implicit def kvDecoder[F[_]: MonadThrow]: KVDecoder[F, VideoScan] = KVDecoder.forProduct2(VideoScan.apply)
}
