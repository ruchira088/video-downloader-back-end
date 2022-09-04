package com.ruchij.api.web.requests

import com.ruchij.api.daos.user.models.Email

final case class ForgotPasswordRequest(email: Email)
