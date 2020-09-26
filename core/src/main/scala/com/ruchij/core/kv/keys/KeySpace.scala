package com.ruchij.core.kv.keys

trait KeySpace[K <: KVStoreKey, V] {
  val name: String
}
