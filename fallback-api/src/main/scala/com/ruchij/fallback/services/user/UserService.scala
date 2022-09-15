package com.ruchij.fallback.services.user

import com.ruchij.api.daos.user.models.Email
import com.ruchij.api.services.authentication.AuthenticationService.Password
import com.ruchij.fallback.daos.user.models.User

trait UserService[F[_]] {
  def create(email: Email, password: Password): F[User]
}
