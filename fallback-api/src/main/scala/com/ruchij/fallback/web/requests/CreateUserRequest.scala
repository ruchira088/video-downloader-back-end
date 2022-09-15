package com.ruchij.fallback.web.requests

import com.ruchij.api.daos.user.models.Email
import com.ruchij.api.services.authentication.AuthenticationService.Password

final case class CreateUserRequest(email: Email, password: Password)
