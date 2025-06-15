package com.ruchij.core.kv.keys

import scala.concurrent.duration.FiniteDuration

trait KVStoreKey {
  val maybeTtl: Option[FiniteDuration] = None
}

object KVStoreKey {
  val KeySeparator: String = "::"

  object KeyList {
    def unapplySeq(key: String): Some[Seq[String]] =
      if (key.trim.isEmpty) Some(Nil) else Some(key.split(KeySeparator).toList)
  }
}
