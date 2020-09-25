package com.ruchij.services.authentication.models

import com.ruchij.services.authentication.AuthenticationService.Secret
import org.joda.time.DateTime

case class AuthenticationToken(secret: Secret, expiresAt: DateTime, createdAt: DateTime, renewals: Long)
