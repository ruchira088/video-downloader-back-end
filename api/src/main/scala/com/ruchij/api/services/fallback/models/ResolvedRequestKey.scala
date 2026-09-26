package com.ruchij.api.services.fallback.models

import com.ruchij.core.kv.keys.{KVStoreKey, KeySpace}

import scala.concurrent.duration._

/** Holds the encoded `RequestResolved` reply to a handled fallback schedule request, so a redelivery of the request
  * re-sends that reply instead of scheduling the video again. */
final case class ResolvedRequestKey(requestId: String) extends KVStoreKey

object ResolvedRequestKey {
  implicit case object ResolvedRequestKeySpace extends KeySpace[ResolvedRequestKey, String] {
    override val name: String = "fallback-sync-resolved-request"
    // As long as SQS can keep the request itself
    override val maybeTtl: Option[FiniteDuration] = Some(14.days)
  }
}
