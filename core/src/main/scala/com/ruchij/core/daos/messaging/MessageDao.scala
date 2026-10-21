package com.ruchij.core.daos.messaging

import java.time.Instant

trait MessageDao[F[_]] {
  def insert(channel: String, payload: String, createdAt: Instant): F[Int]

  def maxId(channel: String): F[Long]

  def findAfter(channel: String, afterId: Long): F[List[(Long, String)]]

  /**
    * Deletes the messages created before `timestamp` that every consumer group of their channel has acknowledged.
    * Messages older than a much longer hard retention are deleted regardless, and consumer groups that have been
    * idle for long enough are expired first, so that groups left behind by stopped instances don't hold messages
    * back for the whole hard retention. Returns the number of messages deleted.
    */
  def deleteBefore(timestamp: Instant): F[Int]
}
