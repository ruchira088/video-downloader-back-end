package com.ruchij.api.daos.credentials.models

import com.ruchij.api.daos.credentials.models.Credentials.HashedPassword
import org.joda.time.DateTime

case class Credentials(userId: String, lastUpdatedAt: DateTime, hashedPassword: HashedPassword)

object Credentials {
  case class HashedPassword(value: String) extends AnyVal
}
