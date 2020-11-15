package com.ruchij.core.messaging.kafka

import com.ruchij.core.config.KafkaConfiguration
import fs2.kafka.{RecordDeserializer, RecordSerializer}

trait Topic[F[_], A] {
  val name: String

  def serializer(kafkaConfiguration: KafkaConfiguration): RecordSerializer[F, A]

  def deserializer(kafkaConfiguration: KafkaConfiguration): RecordDeserializer[F, A]
}

object Topic {
  def apply[F[_], A](implicit topic: Topic[F, A]): Topic[F, A] = topic
}
