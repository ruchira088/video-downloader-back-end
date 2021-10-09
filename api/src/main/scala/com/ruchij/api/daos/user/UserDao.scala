package com.ruchij.api.daos.user

import com.ruchij.api.daos.user.models.{Email, User}

trait UserDao[F[_]] {
  def insert(user: User): F[Int]

  def findByEmail(email: Email): F[Option[User]]

  def findById(userId: String): F[Option[User]]

  def deleteById(userId: String): F[Int]
}
