package com.ruchij.api.services.hashing

import com.ruchij.api.daos.credentials.models.Credentials.HashedPassword
import com.ruchij.api.services.authentication.AuthenticationService.Password

trait PasswordHashingService[F[_]] {
  def checkPassword(password: Password, hashedPassword: HashedPassword): F[Boolean]

  def hashPassword(password: Password): F[HashedPassword]
}
