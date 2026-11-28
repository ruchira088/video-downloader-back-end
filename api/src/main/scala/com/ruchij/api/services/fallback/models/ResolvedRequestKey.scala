package com.ruchij.api.services.fallback.models

import cats.Applicative
import com.ruchij.core.kv.codecs.KVEncoder
import com.ruchij.core.kv.keys.{KVStoreKey, KeySpace, KeySpacedKeyEncoder}

import scala.concurrent.duration.*

/** Holds the encoded `RequestResolved` reply to a handled fallback schedule request, so a redelivery of the request
  * re-sends that reply instead of scheduling the video again.
  */
final case class ResolvedRequestKey(requestId: String) extends KVStoreKey

object ResolvedRequestKey {
  given ResolvedRequestKeySpace: KeySpace[ResolvedRequestKey, String] {
    override val name: String = "fallback-sync-resolved-request"
    // As long as SQS can keep the request itself
    override val maybeTtl: Option[FiniteDuration] = Some(14.days)
  }

  given keySpacedKeyEncoder[F[_]: Applicative]: KeySpacedKeyEncoder[F, ResolvedRequestKey] =
    KeySpacedKeyEncoder.of(ResolvedRequestKeySpace, KVEncoder[F, String].coMap[ResolvedRequestKey](_.requestId))
}
