package com.ruchij.core.messaging.postgres

import io.circe.Codec

import scala.reflect.ClassTag

trait PostgresTopic[A] {
  val channelName: String
  val codec: Codec[A]
}

object PostgresTopic {
  implicit def apply[A](implicit valueCodec: Codec[A], classTag: ClassTag[A]): PostgresTopic[A] =
    new PostgresTopic[A] {
      override val channelName: String = classTag.runtimeClass.getSimpleName.toLowerCase
      override val codec: Codec[A] = valueCodec
    }
}
