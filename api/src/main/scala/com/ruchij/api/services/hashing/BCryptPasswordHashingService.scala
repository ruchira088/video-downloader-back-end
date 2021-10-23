package com.ruchij.api.services.hashing

import cats.effect.Sync
import cats.implicits._
import com.ruchij.api.daos.credentials.models.Credentials.HashedPassword
import com.ruchij.api.services.authentication.AuthenticationService.Password
import org.mindrot.jbcrypt.BCrypt

class BCryptPasswordHashingService[F[_]: Sync] extends PasswordHashingService[F] {

  override def checkPassword(password: Password, hashedPassword: HashedPassword): F[Boolean] =
    Sync[F].delay {
      BCrypt.checkpw(password.value, hashedPassword.value)
    }

  override def hashPassword(password: Password): F[HashedPassword] =
    Sync[F].delay {
      BCrypt.hashpw(password.value, BCrypt.gensalt())
    }
      .map(hashed => HashedPassword(hashed))

}
