package com.ruchij.services.repository

import fs2.Stream

trait RepositoryService[F[_]] {

  type BackedType
  type Key = String

  def write(key: Key, data: Stream[F, Byte]): Stream[F, Unit]

  def read(key: Key, start: Option[Long], end: Option[Long]): F[Option[Stream[F, Byte]]]

  def size(key: Key): F[Option[Long]]

  def list(key: Key): Stream[F, Key]

  def backedType(key: Key): F[BackedType]

  def delete(key: Key): F[Boolean]
}
