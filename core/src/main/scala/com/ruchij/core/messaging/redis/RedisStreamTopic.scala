package com.ruchij.core.messaging.redis

import io.circe.Codec

import scala.reflect.ClassTag

trait RedisStreamTopic[A] {
  val streamKey: String
  val codec: Codec[A]
}

object RedisStreamTopic {
  implicit def apply[A](implicit valueCodec: Codec[A], classTag: ClassTag[A]): RedisStreamTopic[A] =
    new RedisStreamTopic[A] {
      override val streamKey: String = classTag.runtimeClass.getSimpleName.toLowerCase
      override val codec: Codec[A] = valueCodec
    }
}
