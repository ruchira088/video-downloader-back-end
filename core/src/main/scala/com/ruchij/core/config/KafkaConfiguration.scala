package com.ruchij.core.config

import org.http4s.Uri

final case class KafkaConfiguration(private val prefix: String, bootstrapServers: String, schemaRegistry: Uri) {
  def label(value: String): String = s"$prefix-$value"
}