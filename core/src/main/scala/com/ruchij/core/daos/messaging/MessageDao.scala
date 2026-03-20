package com.ruchij.core.daos.messaging

import java.time.Instant

trait MessageDao[F[_]] {
  def insert(channel: String, payload: String, createdAt: Instant): F[Int]

  def maxId(channel: String): F[Long]

  def findAfter(channel: String, afterId: Long): F[List[(Long, String)]]

  def deleteBefore(timestamp: Instant): F[Int]
}
