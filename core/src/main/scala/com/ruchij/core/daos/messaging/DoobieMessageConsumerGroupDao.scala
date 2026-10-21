package com.ruchij.core.daos.messaging

import cats.data.NonEmptyList
import cats.free.Free
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import doobie.free.connection.ConnectionIO
import doobie.implicits.toSqlInterpolator
import doobie.util.fragments.in
import doobie.util.update.Update

import java.time.Instant

object DoobieMessageConsumerGroupDao extends MessageConsumerGroupDao[ConnectionIO] {

  override def createGroup(channel: String, groupId: String, createdAt: Instant): ConnectionIO[Int] =
    sql"""
      INSERT INTO message_queue_consumer_group (channel, group_id, start_id, created_at)
        SELECT $channel, $groupId, COALESCE(MAX(id), 0), $createdAt FROM message_queue WHERE channel = $channel
    """.update.run

  override def lockGroup(channel: String, groupId: String): ConnectionIO[Option[Long]] =
    sql"""
      SELECT start_id FROM message_queue_consumer_group WHERE channel = $channel AND group_id = $groupId FOR UPDATE
    """.query[Long].option

  override def findUnclaimed(
    channel: String,
    groupId: String,
    startId: Long,
    limit: Int
  ): ConnectionIO[List[(Long, String)]] =
    sql"""
      SELECT message_queue.id, message_queue.payload FROM message_queue
        WHERE message_queue.channel = $channel AND message_queue.id > $startId
          AND NOT EXISTS (
            SELECT 1 FROM message_queue_delivery
              WHERE message_queue_delivery.group_id = $groupId
                AND message_queue_delivery.message_id = message_queue.id
          )
        ORDER BY message_queue.id ASC
        LIMIT $limit
    """.query[(Long, String)].to[List]

  override def findExpiredClaims(
    channel: String,
    groupId: String,
    claimedBefore: Instant,
    limit: Int
  ): ConnectionIO[List[(Long, String)]] =
    sql"""
      SELECT message_queue.id, message_queue.payload FROM message_queue
        INNER JOIN message_queue_delivery ON message_queue_delivery.message_id = message_queue.id
        WHERE message_queue.channel = $channel
          AND message_queue_delivery.group_id = $groupId
          AND message_queue_delivery.acknowledged = FALSE
          AND message_queue_delivery.delivered_at < $claimedBefore
        ORDER BY message_queue.id ASC
        LIMIT $limit
    """.query[(Long, String)].to[List]

  override def insertClaims(groupId: String, messageIds: List[Long], claimedAt: Instant): ConnectionIO[Int] =
    Update[(String, Long, Instant)](
      "INSERT INTO message_queue_delivery (group_id, message_id, delivered_at, acknowledged) VALUES (?, ?, ?, FALSE)"
    ).updateMany(messageIds.map(messageId => (groupId, messageId, claimedAt)))

  override def renewClaims(groupId: String, messageIds: List[Long], claimedAt: Instant): ConnectionIO[Int] =
    NonEmptyList.fromList(messageIds).fold[ConnectionIO[Int]](Free.pure(0)) { ids =>
      (sql"UPDATE message_queue_delivery SET delivered_at = $claimedAt WHERE group_id = $groupId AND " ++
        in(fr"message_id", ids)).update.run
    }

  override def acknowledge(groupId: String, messageIds: List[Long]): ConnectionIO[Int] =
    NonEmptyList.fromList(messageIds).fold[ConnectionIO[Int]](Free.pure(0)) { ids =>
      (sql"UPDATE message_queue_delivery SET acknowledged = TRUE WHERE group_id = $groupId AND " ++
        in(fr"message_id", ids)).update.run
    }
}
