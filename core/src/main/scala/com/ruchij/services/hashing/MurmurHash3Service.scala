package com.ruchij.services.hashing

import java.util.Base64

import cats.effect.{Blocker, ContextShift, Sync}
import cats.implicits._

import scala.util.hashing.MurmurHash3

class MurmurHash3Service[F[_]: Sync: ContextShift](blocker: Blocker) extends HashingService[F] {
  override def hash(value: String): F[String] =
    blocker
      .delay[F, String] {
        Base64.getEncoder
          .encodeToString { MurmurHash3.stringHash(value).toString.getBytes }
      }
      .map { _.trim.filter(_.isLetterOrDigit) }
}