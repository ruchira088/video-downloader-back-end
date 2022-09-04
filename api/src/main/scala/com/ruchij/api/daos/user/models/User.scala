package com.ruchij.api.daos.user.models

import org.joda.time.DateTime

final case class User(id: String, createdAt: DateTime, firstName: String, lastName: String, email: Email, role: Role) {
  val nonAdminUserId: Option[String] = if (role == Role.Admin) None else Some(id)
}
