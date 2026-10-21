package com.ruchij.core.daos.messaging

import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import doobie.free.connection.ConnectionIO
import doobie.implicits.toSqlInterpolator

import java.time.{Duration, Instant}

object DoobieMessageDao extends MessageDao[ConnectionIO] {

  /** Mirrors Kafka's default retention.ms */
  val HardRetention: Duration = Duration.ofDays(7)

  /**
    * How long a consumer group may go without polling before it is deleted. Kafka keeps idle groups for 7 days
    * (offsets.retention.minutes), but the API and batch instances use a group id per instance, so every restart
    * leaves groups behind that would otherwise hold messages back for that long.
    */
  val IdleGroupExpiry: Duration = Duration.ofDays(1)

  override def insert(channel: String, payload: String, createdAt: Instant): ConnectionIO[Int] =
    sql"INSERT INTO message_queue (channel, payload, created_at) VALUES ($channel, $payload, $createdAt)".update.run

  override def maxId(channel: String): ConnectionIO[Long] =
    sql"SELECT COALESCE(MAX(id), 0) FROM message_queue WHERE channel = $channel"
      .query[Long]
      .unique

  override def findAfter(channel: String, afterId: Long): ConnectionIO[List[(Long, String)]] =
    sql"SELECT id, payload FROM message_queue WHERE channel = $channel AND id > $afterId ORDER BY id ASC"
      .query[(Long, String)]
      .to[List]

  override def deleteBefore(timestamp: Instant): ConnectionIO[Int] =
    for {
      _ <- expireIdleGroups(timestamp.minus(IdleGroupExpiry))
      hardRetentionCutoff = timestamp.minus(HardRetention)

      // A group is done with a message once its start id has moved past it, or once it has acknowledged it
      deleted <- sql"""
        DELETE FROM message_queue
          WHERE created_at < $timestamp
            AND (
              created_at < $hardRetentionCutoff
                OR NOT EXISTS (
                  SELECT 1 FROM message_queue_consumer_group
                    WHERE message_queue_consumer_group.channel = message_queue.channel
                      AND message_queue_consumer_group.start_id < message_queue.id
                      AND NOT EXISTS (
                        SELECT 1 FROM message_queue_delivery
                          WHERE message_queue_delivery.group_id = message_queue_consumer_group.group_id
                            AND message_queue_delivery.message_id = message_queue.id
                            AND message_queue_delivery.acknowledged = TRUE
                      )
                )
            )
      """.update.run
    } yield deleted

  private def expireIdleGroups(polledBefore: Instant): ConnectionIO[Int] =
    for {
      // Deliveries only reference the group id, so match them to the group's channel through their message
      _ <- sql"""
        DELETE FROM message_queue_delivery
          WHERE EXISTS (
            SELECT 1 FROM message_queue_consumer_group
              INNER JOIN message_queue ON message_queue.channel = message_queue_consumer_group.channel
              WHERE message_queue_consumer_group.last_polled_at < $polledBefore
                AND message_queue_consumer_group.group_id = message_queue_delivery.group_id
                AND message_queue.id = message_queue_delivery.message_id
          )
      """.update.run

      expired <- sql"DELETE FROM message_queue_consumer_group WHERE last_polled_at < $polledBefore".update.run
    } yield expired
}
