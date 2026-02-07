package com.ruchij.api.daos.credentials.models

import com.ruchij.api.daos.credentials.models.Credentials.HashedPassword
import java.time.Instant

final case class Credentials(userId: String, lastUpdatedAt: Instant, hashedPassword: HashedPassword)

object Credentials {
  final case class HashedPassword(value: String) extends AnyVal
}
