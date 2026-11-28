package com.ruchij.core.daos.workers.models

import cats.{Monad, MonadThrow}
import com.ruchij.core.daos.workers.models.VideoScan.ScanStatus
import com.ruchij.core.kv.codecs.{KVDecoder, KVEncoder}
import com.ruchij.core.types.{EnumEntry, EnumValues}
import java.time.Instant

final case class VideoScan(updatedAt: Instant, status: ScanStatus)

object VideoScan {
  enum ScanStatus extends EnumEntry {
    case Idle, Scheduled, InProgress, Error
  }

  object ScanStatus extends EnumValues[ScanStatus]

  given kvEncoder[F[_]: Monad]: KVEncoder[F, VideoScan] =
    KVEncoder.forProduct2((videoScan: VideoScan) => (videoScan.updatedAt, videoScan.status))

  given kvDecoder[F[_]: MonadThrow]: KVDecoder[F, VideoScan] = KVDecoder.forProduct2(VideoScan.apply)
}
