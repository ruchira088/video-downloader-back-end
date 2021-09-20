package com.ruchij.api.daos.user

import com.ruchij.api.daos.user.models.{Email, User}
import com.ruchij.core.daos.doobie.DoobieCustomMappings.{dateTimeGet, dateTimePut, enumGet, enumPut}
import doobie.free.connection.ConnectionIO
import doobie.implicits.toSqlInterpolator

object DoobieUserDao extends UserDao[ConnectionIO] {
  val SelectQuery =
    fr"SELECT id, created_at, first_name, last_name, email, role FROM api_user"

  override def insert(user: User): ConnectionIO[Int] =
    sql"""
        INSERT INTO api_user (id, created_at, first_name, last_name, email, role)
            VALUES (
              ${user.id},
              ${user.createdAt},
              ${user.firstName},
              ${user.lastName},
              ${user.email},
              ${user.role}
            )    
    """.update.run

  override def findByEmail(email: Email): ConnectionIO[Option[User]] =
    (SelectQuery ++ fr"WHERE email = $email").query[User].option

  override def findById(userId: String): ConnectionIO[Option[User]] =
    (SelectQuery ++ fr"WHERE id = $userId").query[User].option
}
