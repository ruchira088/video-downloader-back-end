package com.ruchij.api.daos.resettoken.models

import java.time.Instant

final case class CredentialsResetToken(userId: String, createdAt: Instant, token: String)
