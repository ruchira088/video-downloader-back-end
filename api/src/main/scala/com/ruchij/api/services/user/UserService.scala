package com.ruchij.api.services.user

import com.ruchij.api.daos.user.models.{Email, User}
import com.ruchij.api.services.authentication.AuthenticationService.Password

trait UserService[F[_]] {
  def create(firstName: String, lastName: String, email: Email, password: Password): F[User]
}
