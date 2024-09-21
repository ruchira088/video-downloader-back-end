package com.ruchij.api.daos.resettoken

import com.ruchij.core.daos.doobie.DoobieCustomMappings.{dateTimeGet, dateTimePut}
import com.ruchij.api.daos.resettoken.models.CredentialsResetToken
import doobie.free.connection.ConnectionIO
import doobie.generic.auto._
import doobie.implicits.toSqlInterpolator

object DoobieCredentialsResetTokenDao extends CredentialsResetTokenDao[ConnectionIO] {

  override def insert(credentialsResetToken: CredentialsResetToken): ConnectionIO[Int] =
    sql"""
      INSERT INTO credentials_reset_token (user_id, created_at, token)
        VALUES (${credentialsResetToken.userId}, ${credentialsResetToken.createdAt}, ${credentialsResetToken.token})
    """
      .update
      .run

  override def find(userId: String, token: String): ConnectionIO[Option[CredentialsResetToken]] =
    sql"SELECT user_id, created_at, token FROM credentials_reset_token WHERE user_id = $userId AND token = $token"
      .query[CredentialsResetToken]
      .option

  override def delete(userId: String, token: String): ConnectionIO[Int] =
    sql"DELETE FROM credentials_reset_token WHERE user_id = $userId AND token = $token"
      .update
      .run
}
