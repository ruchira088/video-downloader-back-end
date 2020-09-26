package com.ruchij.core.services.hashing

trait HashingService[F[_]] {
  def hash(value: String): F[String]
}
