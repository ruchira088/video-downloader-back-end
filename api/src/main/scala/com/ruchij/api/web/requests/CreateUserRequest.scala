package com.ruchij.api.web.requests

import com.ruchij.api.daos.user.models.Email
import com.ruchij.api.services.authentication.AuthenticationService.Password

case class CreateUserRequest(firstName: String, lastName: String, email: Email, password: Password)
