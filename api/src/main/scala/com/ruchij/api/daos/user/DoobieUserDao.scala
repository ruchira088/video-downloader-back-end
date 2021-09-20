package com.ruchij.api.daos.user

import com.ruchij.api.daos.user.models.{Email, User}
import doobie.free.connection.ConnectionIO

object DoobieUserDao extends UserDao[ConnectionIO] {
  override def insert(user: User): ConnectionIO[Int] = ???

  override def findByEmail(email: Email): ConnectionIO[Option[User]] = ???

  override def findById(userId: String): ConnectionIO[Option[User]] = ???
}
