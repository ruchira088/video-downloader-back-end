package com.ruchij.core.config

import com.ruchij.core.config.PureConfigReaders.given
import org.http4s.Uri
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

final case class SpaSiteRendererConfiguration(uri: Uri)

object SpaSiteRendererConfiguration {
  given configReader: ConfigReader[SpaSiteRendererConfiguration] = deriveReader[SpaSiteRendererConfiguration]
}
