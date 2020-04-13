package com.ruchij.services.repository

import fs2.Stream

trait RepositoryService[F[_]] {
  def write(key: String, data: Stream[F, Byte]): Stream[F, Unit]

  def read(key: String, start: Long, end: Long): F[Option[Stream[F, Byte]]]
}
