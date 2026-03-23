package com.ruchij.core.messaging.db

import io.circe.Codec

import scala.reflect.ClassTag

trait DoobieTopic[A] {
  val topicName: String
  val codec: Codec[A]
}

object DoobieTopic {
  implicit def apply[A](implicit valueCodec: Codec[A], classTag: ClassTag[A]): DoobieTopic[A] =
    new DoobieTopic[A] {
      override val topicName: String = classTag.runtimeClass.getSimpleName.toLowerCase
      override val codec: Codec[A] = valueCodec
    }
}
