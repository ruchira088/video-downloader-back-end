package com.ruchij.api.web.requests

import com.ruchij.api.services.authentication.AuthenticationService.Password

final case class ResetPasswordRequest(token: String, password: Password)
