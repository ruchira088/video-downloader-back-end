package com.ruchij.fallback.daos.user.models

import com.ruchij.api.daos.user.models.Email
import org.joda.time.DateTime

final case class User(id: String, createdAt: DateTime, email: Email)
