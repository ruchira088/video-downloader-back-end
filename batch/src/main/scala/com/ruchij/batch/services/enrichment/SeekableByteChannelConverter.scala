package com.ruchij.batch.services.enrichment

import cats.effect.Sync
import fs2.io.file.Path
import org.jcodec.common.AutoFileChannelWrapper
import org.jcodec.common.io.SeekableByteChannel

trait SeekableByteChannelConverter[F[_], -A] {
  def convert(value: A): F[SeekableByteChannel]
}

object SeekableByteChannelConverter {
  implicit def pathSeekableByteChannelConverter[F[_]: Sync]: SeekableByteChannelConverter[F, Path] =
    (path: Path) => Sync[F].blocking(new AutoFileChannelWrapper(path.toNioPath.toFile))
}