package com.ruchij.core.config

import com.ruchij.core.config.PureConfigReaders._
import org.http4s.Uri
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

final case class KafkaConfiguration(private val prefix: String, bootstrapServers: String, schemaRegistry: Uri) {
  def topicName(value: String): String = s"$prefix-$value"
}

object KafkaConfiguration {
  implicit val configReader: ConfigReader[KafkaConfiguration] = deriveReader[KafkaConfiguration]
}
