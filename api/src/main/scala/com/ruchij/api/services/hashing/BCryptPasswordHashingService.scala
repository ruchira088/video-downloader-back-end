package com.ruchij.api.services.hashing

import cats.implicits._
import cats.effect.{Blocker, ContextShift, Sync}
import com.ruchij.api.daos.credentials.models.Credentials.HashedPassword
import com.ruchij.api.services.authentication.AuthenticationService.Password
import org.mindrot.jbcrypt.BCrypt

class BCryptPasswordHashingService[F[_]: ContextShift: Sync](blockerCPU: Blocker) extends PasswordHashingService[F] {

  override def checkPassword(password: Password, hashedPassword: HashedPassword): F[Boolean] =
    blockerCPU.delay {
      BCrypt.checkpw(password.value, hashedPassword.value)
    }

  override def hashPassword(password: Password): F[HashedPassword] =
    blockerCPU.delay {
      BCrypt.hashpw(password.value, BCrypt.gensalt())
    }
      .map(hashed => HashedPassword(hashed))

}
