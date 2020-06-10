package com.ruchij.services.enrichment

import java.nio.file.Path

import cats.effect.Sync
import org.jcodec.common.AutoFileChannelWrapper
import org.jcodec.common.io.SeekableByteChannel

trait SeekableByteChannelConverter[F[_], -A] {
  def convert(value: A): F[SeekableByteChannel]
}

object SeekableByteChannelConverter {
  implicit def pathSeekableByteChannelConverter[F[_]: Sync]: SeekableByteChannelConverter[F, Path] =
    (path: Path) => Sync[F].delay(new AutoFileChannelWrapper(path.toFile))
}