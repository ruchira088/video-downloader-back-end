package com.ruchij.api.services.user

import cats.FlatMap
import cats.implicits._
import com.ruchij.api.daos.resettoken.models.CredentialsResetToken
import com.ruchij.api.daos.user.models.{Email, User}
import com.ruchij.api.services.authentication.AuthenticationService.Password
import com.ruchij.api.services.fallback.FallbackUserRevoker

/** Revokes the user's fallback account after each successful password reset or user deletion, which the fallback's
  * Cognito login would otherwise never learn of. A decorator, so `UserServiceImpl` stays unaware of the fallback. */
class FallbackRevokingUserService[F[_]: FlatMap](userService: UserService[F], fallbackUserRevoker: FallbackUserRevoker[F])
    extends UserService[F] {

  override def create(firstName: String, lastName: String, email: Email, password: Password): F[User] =
    userService.create(firstName, lastName, email, password)

  override def getById(userId: String): F[User] = userService.getById(userId)

  override def getByEmail(email: Email): F[User] = userService.getByEmail(email)

  override def forgotPassword(email: Email): F[CredentialsResetToken] = userService.forgotPassword(email)

  override def resetPassword(userId: String, resetToken: String, password: Password): F[User] =
    userService.resetPassword(userId, resetToken, password).flatTap(fallbackUserRevoker.revoke)

  override def delete(userId: String, adminUser: User): F[User] =
    userService.delete(userId, adminUser).flatTap(fallbackUserRevoker.revoke)
}
