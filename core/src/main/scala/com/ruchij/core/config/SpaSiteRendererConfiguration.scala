package com.ruchij.core.config

import com.ruchij.core.config.PureConfigReaders.*
import org.http4s.Uri
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

final case class SpaSiteRendererConfiguration(uri: Uri)

object SpaSiteRendererConfiguration {
  implicit val configReader: ConfigReader[SpaSiteRendererConfiguration] = deriveReader[SpaSiteRendererConfiguration]
}
