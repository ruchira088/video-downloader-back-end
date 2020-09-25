package com.ruchij.kv.keys

trait KeySpace[K <: KVStoreKey, V] {
  val name: String
}
