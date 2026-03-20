package com.ruchij.core.daos.messaging

import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import doobie.free.connection.ConnectionIO
import doobie.implicits.toSqlInterpolator

import java.time.Instant

object DoobieMessageDao extends MessageDao[ConnectionIO] {

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
    sql"DELETE FROM message_queue WHERE created_at < $timestamp".update.run
}
