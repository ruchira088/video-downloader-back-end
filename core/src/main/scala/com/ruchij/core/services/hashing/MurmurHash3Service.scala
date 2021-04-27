package com.ruchij.core.services.hashing

import cats.effect.{Blocker, ContextShift, Sync}

import scala.util.hashing.MurmurHash3

class MurmurHash3Service[F[_]: Sync: ContextShift](blocker: Blocker) extends HashingService[F] {
  override def hash(value: String): F[String] =
    blocker
      .delay[F, String] {
        Integer.toHexString(MurmurHash3.stringHash(value))
      }
}