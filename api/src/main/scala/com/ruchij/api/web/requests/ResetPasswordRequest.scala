package com.ruchij.api.web.requests

import com.ruchij.api.daos.user.models.Email

case class ResetPasswordRequest(email: Email)
