package com.ruchij.api.daos.credentials.models

import com.ruchij.api.daos.credentials.models.Credentials.HashedPassword
import doobie.Meta
import java.time.Instant

final case class Credentials(userId: String, lastUpdatedAt: Instant, hashedPassword: HashedPassword)

object Credentials {
  final case class HashedPassword(value: String) extends AnyVal

  object HashedPassword {
    given hashedPasswordMeta: Meta[HashedPassword] = Meta[String].timap(HashedPassword.apply)(_.value)
  }
}
