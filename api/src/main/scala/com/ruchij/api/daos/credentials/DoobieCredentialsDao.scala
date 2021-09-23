package com.ruchij.api.daos.credentials

import com.ruchij.api.daos.credentials.models.Credentials
import com.ruchij.core.daos.doobie.DoobieCustomMappings.{dateTimeGet, dateTimePut}
import doobie.free.connection.ConnectionIO
import doobie.implicits.toSqlInterpolator

object DoobieCredentialsDao extends CredentialsDao[ConnectionIO] {
  override def insert(credentials: Credentials): ConnectionIO[Int] =
    sql"""
      INSERT INTO credentials (user_id, last_updated_at, hashed_password)
        VALUES (${credentials.userId}, ${credentials.lastUpdatedAt}, ${credentials.hashedPassword})
    """
      .update
      .run

  override def findCredentialsByUserId(userId: String): ConnectionIO[Option[Credentials]] =
    sql"SELECT user_id, last_updated_at, hashed_password FROM credentials WHERE user_id = $userId"
      .query[Credentials]
      .option
}
