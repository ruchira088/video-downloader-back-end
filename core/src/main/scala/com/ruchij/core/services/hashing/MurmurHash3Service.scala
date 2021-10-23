package com.ruchij.core.services.hashing

import cats.effect.Sync

import scala.util.hashing.MurmurHash3

class MurmurHash3Service[F[_]: Sync] extends HashingService[F] {
  override def hash(value: String): F[String] =
    Sync[F]
      .delay[String] {
        Integer.toHexString(MurmurHash3.stringHash(value))
      }
}