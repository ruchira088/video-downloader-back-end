package com.ruchij.api.services.models

import com.ruchij.api.daos.user.models.User

sealed trait Context {
  val requestId: String
}

object Context {
  final case class RequestContext(requestId: String) extends Context
  final case class AuthenticatedRequestContext(user: User, requestId: String) extends Context
}
