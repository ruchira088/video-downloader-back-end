package com.ruchij.core.config

import com.ruchij.core.config.PureConfigReaders.given
import org.http4s.Uri
import pureconfig.ConfigReader

final case class KafkaConfiguration(private val prefix: String, bootstrapServers: String, schemaRegistry: Uri)
    derives ConfigReader {
  def topicName(value: String): String = s"$prefix-$value"
}
