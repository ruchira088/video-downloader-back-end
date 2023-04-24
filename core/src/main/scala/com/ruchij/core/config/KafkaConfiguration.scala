package com.ruchij.core.config

import org.http4s.Uri

final case class KafkaConfiguration(private val topicPrefix: String, bootstrapServers: String, schemaRegistry: Uri) {
  def topicName(name: String): String = s"$topicPrefix-$name"
}