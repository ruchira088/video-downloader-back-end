package com.ruchij.api.services.models

import com.ruchij.api.daos.user.models.User

sealed trait Context {
  val requestId: String
}

object Context {
  case class RequestContext(requestId: String) extends Context
  case class UserContext(user: User, requestId: String) extends Context
}
