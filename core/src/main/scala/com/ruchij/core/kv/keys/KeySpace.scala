package com.ruchij.core.kv.keys

import scala.concurrent.duration.FiniteDuration

trait KeySpace[K <: KVStoreKey, V] {
  val name: String

  val maybeTtl: Option[FiniteDuration] = None
}
