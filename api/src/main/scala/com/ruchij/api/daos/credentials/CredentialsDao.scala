package com.ruchij.api.daos.credentials

import com.ruchij.api.daos.credentials.models.Credentials

trait CredentialsDao[F[_]] {
  def insert(credentials: Credentials): F[Int]

  def findCredentialsByUserId(userId: String): F[Option[Credentials]]

  def deleteByUserId(userId: String): F[Int]

  def update(credentials: Credentials): F[Int]
}
