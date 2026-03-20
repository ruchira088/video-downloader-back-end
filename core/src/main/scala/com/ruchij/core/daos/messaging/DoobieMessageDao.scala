package com.ruchij.core.daos.messaging

import doobie.free.connection.ConnectionIO
import doobie.implicits.toSqlInterpolator

object DoobieMessageDao extends MessageDao[ConnectionIO] {

  override def insert(channel: String, payload: String): ConnectionIO[Int] =
    sql"INSERT INTO message_queue (channel, payload) VALUES ($channel, $payload)"
      .update.run

  override def maxId(channel: String): ConnectionIO[Long] =
    sql"SELECT COALESCE(MAX(id), 0) FROM message_queue WHERE channel = $channel"
      .query[Long].unique

  override def findAfter(channel: String, afterId: Long): ConnectionIO[List[(Long, String)]] =
    sql"SELECT id, payload FROM message_queue WHERE channel = $channel AND id > $afterId ORDER BY id ASC"
      .query[(Long, String)].to[List]
}
