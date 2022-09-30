package com.ruchij.api.services.fallback.models

import com.ruchij.api.daos.user.models.Email
import org.joda.time.DateTime

final case class UserInformation(
  id: String,
  createdAt: DateTime,
  email: Email,
  firstName: String,
  lastName: Option[String]
)
