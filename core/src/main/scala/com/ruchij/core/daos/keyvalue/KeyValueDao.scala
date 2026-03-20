package com.ruchij.core.daos.keyvalue

import java.time.Instant

trait KeyValueDao[F[_]] {
  def insert(key: String, value: String, maybeExpiresAt: Option[Instant]): F[Int]

  def find(key: String, timestamp: Instant): F[Option[String]]

  def delete(key: String): F[Int]

  def deleteExpiredKeys(timestamp: Instant): F[Int]
}
