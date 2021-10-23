package com.ruchij.api.daos.resettoken.models

import org.joda.time.DateTime

case class CredentialsResetToken(userId: String, createdAt: DateTime, token: String)
