package com.ruchij.kv.keys

import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadError}
import com.ruchij.kv.codecs.{KVDecoder, KVEncoder}
import shapeless.{Generic, HList}

sealed trait KVStoreKey[A <: KVStoreKey[A]] {
  val keyName: String
}

object KVStoreKey {
  val KeySeparator: String = "::"

  object KeyList {
    def unapplySeq(key: String): Some[Seq[String]] =
      if (key.trim.isEmpty) Some(Nil) else Some(key.split(KeySeparator).toList)
  }

  case class DownloadProgressKey(videoId: String) extends KVStoreKey[DownloadProgressKey] {
    override val keyName: String = KeySpace[DownloadProgressKey].name + KeySeparator + videoId
  }

  implicit def kvStoreKeyEncoder[F[_]: Applicative, A <: KVStoreKey[A]]: KVEncoder[F, A] =
    KVEncoder[F, String].coMap[KVStoreKey[A]](_.keyName)

  implicit def kvStoreKeyDecoder[F[_]: MonadError[*[_], Throwable], A <: KVStoreKey[A], Repr <: HList](
    implicit generic: Generic.Aux[A, Repr],
    kvDecoder: KVDecoder[F, Repr],
    keySpace: KeySpace[A, _]
  ): KVDecoder[F, A] = {
    case KeyList(KeySpace(`keySpace`), keys @ _*) =>
      kvDecoder.decode(keys.mkString(KeySeparator)).map(generic.from)

    case value =>
      ApplicativeError[F, Throwable].raiseError(
        new IllegalArgumentException(s"""Unable to parse "$value" as a ${classOf[KVStoreKey[_]].getSimpleName}""")
      )
  }
}
