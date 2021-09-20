package com.ruchij.api.daos.credentials

import com.ruchij.api.daos.credentials.models.Credentials
import doobie.free.connection.ConnectionIO

object DoobieCredentialsDao extends CredentialsDao[ConnectionIO] {
  override def insert(credentials: Credentials): ConnectionIO[Int] = ???

  override def findCredentialsByUserId(userId: String): ConnectionIO[Option[Credentials]] = ???
}
