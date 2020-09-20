package com.ruchij.kv.keys

import cats.{ApplicativeError, MonadError}
import cats.implicits._
import com.ruchij.kv.codecs.KVDecoder
import com.ruchij.kv.keys.KeySpace.DownloadProgress

sealed trait KVStoreKey {
  val keySpace: KeySpace
}

object KVStoreKey {
  val KeySeparator: String = ":"

  object KeyList {
    def unapplySeq(key: String): Some[Seq[String]] =
      if (key.trim.isEmpty) Some(Nil) else Some(key.split(KeySeparator).toList)
  }

  case class DownloadProgressKey(videoId: String) extends KVStoreKey {
    override val keySpace: KeySpace = DownloadProgress
  }

  implicit def kvStoreKeyDecoder[F[_]: MonadError[*[_], Throwable]]: KVDecoder[F, KVStoreKey] = {
    case value @ KeyList(KeySpace(DownloadProgress), _*) =>
      KVDecoder[F, DownloadProgressKey].decode(value).map(identity)

    case value =>
      ApplicativeError[F, Throwable].raiseError(
        new IllegalArgumentException(s"""Unable to parse "$value" as a ${classOf[KVStoreKey].getSimpleName}""")
      )
  }
}
