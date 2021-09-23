package com.ruchij.api.daos.user.models

import org.joda.time.DateTime

case class User(id: String, createdAt: DateTime, firstName: String, lastName: String, email: Email, role: Role)
