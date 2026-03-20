package com.ruchij.core.daos.keyvalue

import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import doobie.free.connection.ConnectionIO
import doobie.implicits.toSqlInterpolator

import java.time.Instant

object DoobieKeyValueDao extends KeyValueDao[ConnectionIO] {

  override def insert(key: String, value: String, maybeExpiresAt: Option[Instant]): ConnectionIO[Int] =
    sql"""INSERT INTO key_value_store (store_key, store_value, expires_at) VALUES ($key, $value, $maybeExpiresAt)
          ON CONFLICT (store_key) DO UPDATE SET store_value = $value, expires_at = $maybeExpiresAt"""
      .update.run

  override def find(key: String, currentTimestamp: Instant): ConnectionIO[Option[String]] =
    sql"""SELECT store_value FROM key_value_store WHERE store_key = $key AND (expires_at IS NULL OR expires_at > $currentTimestamp)"""
      .query[String].option

  override def delete(key: String): ConnectionIO[Int] =
    sql"""DELETE FROM key_value_store WHERE store_key = $key"""
      .update.run

  override def deleteExpiredKeys(timestamp: Instant): ConnectionIO[Int] =
    sql"""DELETE FROM key_value_store WHERE expires_at IS NOT NULL AND expires_at <= $timestamp"""
      .update.run
}
