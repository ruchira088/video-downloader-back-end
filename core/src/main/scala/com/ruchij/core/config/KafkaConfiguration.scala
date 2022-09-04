package com.ruchij.core.config

import org.http4s.Uri

final case class KafkaConfiguration(bootstrapServers: String, schemaRegistry: Uri)
