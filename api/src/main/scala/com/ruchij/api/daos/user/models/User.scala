package com.ruchij.api.daos.user.models

import java.time.Instant

final case class User(id: String, createdAt: Instant, firstName: String, lastName: String, email: Email, role: Role) {
  val nonAdminUserId: Option[String] = if (role == Role.Admin) None else Some(id)
}
