package com.ruchij.api.services.authentication.models

import cats.{Applicative, Monad, MonadThrow}
import com.ruchij.api.services.authentication.AuthenticationService.Secret
import com.ruchij.core.kv.codecs.{KVDecoder, KVEncoder}
import com.ruchij.core.kv.keys.{KVStoreKey, KeySpace, KeySpacedKeyEncoder}

import java.time.Instant
import scala.concurrent.duration.*
import scala.language.postfixOps

final case class AuthenticationToken(
  userId: String,
  secret: Secret,
  expiresAt: Instant,
  issuedAt: Instant,
  renewals: Long,
  credentialsLastUpdatedAt: Instant
)

object AuthenticationToken {
  private given secretKVEncoder[F[_]: Applicative]: KVEncoder[F, Secret] = KVEncoder[F, String].coMap(_.value)

  private given secretKVDecoder[F[_]: Applicative]: KVDecoder[F, Secret] =
    KVDecoder[F, String].map(Secret.apply)

  final case class AuthenticationTokenKey(secret: Secret) extends KVStoreKey

  object AuthenticationTokenKey {
    given keySpacedKeyEncoder[F[_]: Applicative]: KeySpacedKeyEncoder[F, AuthenticationTokenKey] =
      KeySpacedKeyEncoder.of(AuthenticationKeySpace, secretKVEncoder[F].coMap[AuthenticationTokenKey](_.secret))
  }

  given AuthenticationKeySpace: KeySpace[AuthenticationTokenKey, AuthenticationToken] {
    override val name: String = "authentication"

    override val maybeTtl: Option[FiniteDuration] = Some(45 days)
  }

  given kvEncoder[F[_]: Monad]: KVEncoder[F, AuthenticationToken] =
    KVEncoder.forProduct6 { (token: AuthenticationToken) =>
      (token.userId, token.secret, token.expiresAt, token.issuedAt, token.renewals, token.credentialsLastUpdatedAt)
    }

  given kvDecoder[F[_]: MonadThrow]: KVDecoder[F, AuthenticationToken] =
    KVDecoder.forProduct6(AuthenticationToken.apply)
}
