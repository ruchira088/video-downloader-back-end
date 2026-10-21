package com.ruchij.core.messaging.models

/** A message received by a consumer group, carrying what's needed to acknowledge it once it has been processed. */
final case class ConsumerGroupRecord[Id, A](groupId: String, id: Id, value: A)
