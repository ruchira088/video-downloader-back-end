package com.ruchij.api.daos.user

import com.ruchij.api.daos.user.models.{Email, User}

trait UserDao[F[_]] {
  def insert(user: User): F[Int]

  def findByEmail(email: Email): F[Option[User]]

  def findById(userId: String): F[Option[User]]

  def deleteById(userId: String): F[Int]

  /** Returns the IDs of the admins, locking their rows until the end of the transaction */
  def lockAdminIds: F[List[String]]
}
