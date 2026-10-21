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
    * messages at a time. Returns the id the group starts from, or None if the group doesn't exist.
    */
  def lockGroup(channel: String, groupId: String): F[Option[Long]]

  /** Records that the group polled at `polledAt`, unless it is already recorded as polling after `staleBefore`. */
  def markPolled(channel: String, groupId: String, polledAt: Instant, staleBefore: Instant): F[Int]

  /**
    * Moves the group's start id up to the last message of the unbroken run of messages after `startId` that the
    * group has acknowledged, and drops the claims it moved past. It stops at messages created at or after
    * `createdBefore`, since a message with a lower id may still commit until then. Returns the new start id, if it
    * moved.
    */
  def advanceStartId(channel: String, groupId: String, startId: Long, createdBefore: Instant): F[Option[Long]]

  /** Messages after `startId` that the group hasn't claimed yet, in id order. */
  def findUnclaimed(channel: String, groupId: String, startId: Long, limit: Int): F[List[(Long, String)]]

  /** Messages the group claimed before `claimedBefore` and never acknowledged, in id order. */
  def findExpiredClaims(channel: String, groupId: String, claimedBefore: Instant, limit: Int): F[List[(Long, String)]]

  def insertClaims(groupId: String, consumerId: String, messageIds: List[Long], claimedAt: Instant): F[Int]

  def renewClaims(groupId: String, consumerId: String, messageIds: List[Long], claimedAt: Instant): F[Int]

  def acknowledge(groupId: String, messageIds: List[Long]): F[Int]

  /** Deletes the consumer's unacknowledged claims, so that the group's other consumers can claim them at once. */
  def releaseClaims(groupId: String, consumerId: String): F[Int]
}
