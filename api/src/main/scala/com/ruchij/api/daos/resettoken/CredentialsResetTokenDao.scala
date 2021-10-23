package com.ruchij.api.daos.resettoken

import com.ruchij.api.daos.resettoken.models.CredentialsResetToken

trait CredentialsResetTokenDao[F[_]] {
  def insert(credentialsResetToken: CredentialsResetToken): F[Int]

  def find(userId: String, token: String): F[Option[CredentialsResetToken]]

  def delete(userId: String, token: String): F[Int]
}
