package com.ruchij.core.daos.messaging

import java.time.Instant

trait MessageConsumerGroupDao[F[_]] {

  /**
    * Creates the consumer group, starting it from the latest message on the channel. Fails with a unique violation
    * if the group already exists.
    */
  def createGroup(channel: String, groupId: String, createdAt: Instant): F[Int]

  /**
    * Locks the consumer group row until the end of the transaction, so that only one consumer of the group claims
    * messages at a time. Returns the id the group started from, or None if the group doesn't exist.
    */
  def lockGroup(channel: String, groupId: String): F[Option[Long]]

  /** Messages after `startId` that the group hasn't claimed yet, in id order. */
  def findUnclaimed(channel: String, groupId: String, startId: Long, limit: Int): F[List[(Long, String)]]

  /** Messages the group claimed before `claimedBefore` and never acknowledged, in id order. */
  def findExpiredClaims(channel: String, groupId: String, claimedBefore: Instant, limit: Int): F[List[(Long, String)]]

  def insertClaims(groupId: String, messageIds: List[Long], claimedAt: Instant): F[Int]

  def renewClaims(groupId: String, messageIds: List[Long], claimedAt: Instant): F[Int]

  def acknowledge(groupId: String, messageIds: List[Long]): F[Int]
}
