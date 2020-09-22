package com.ruchij.kv.codecs

trait KVCodec[F[_], A] extends KVDecoder[F, A] with KVEncoder[F, A]

object KVCodec {
  implicit def apply[F[_], A](implicit kvDecoder: KVDecoder[F, A], kvEncoder: KVEncoder[F, A]): KVCodec[F, A] =
    new KVCodec[F, A] {
      override def decode(value: String): F[A] = kvDecoder.decode(value)

      override def encode(value: A): F[String] = kvEncoder.encode(value)
    }
}
