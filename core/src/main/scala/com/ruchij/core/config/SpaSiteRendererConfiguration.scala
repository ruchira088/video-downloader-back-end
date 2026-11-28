package com.ruchij.core.config

import com.ruchij.core.config.PureConfigReaders.given
import org.http4s.Uri
import pureconfig.ConfigReader

final case class SpaSiteRendererConfiguration(uri: Uri) derives ConfigReader
