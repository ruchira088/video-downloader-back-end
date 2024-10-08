package com.ruchij.api.daos.credentials

import com.ruchij.api.daos.credentials.models.Credentials
import com.ruchij.core.daos.doobie.DoobieCustomMappings.{dateTimeGet, dateTimePut}
import doobie.free.connection.ConnectionIO
import doobie.generic.auto._
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

  override def deleteByUserId(userId: String): ConnectionIO[Int] =
    sql"DELETE FROM credentials WHERE user_id = $userId".update.run

  override def update(credentials: Credentials): ConnectionIO[Int] =
    sql"""
      UPDATE credentials
        SET last_updated_at = ${credentials.lastUpdatedAt}, hashed_password = ${credentials.hashedPassword}
        WHERE user_id = ${credentials.userId}
    """
      .update
      .run
}
