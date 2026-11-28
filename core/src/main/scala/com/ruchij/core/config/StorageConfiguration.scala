package com.ruchij.core.config

import com.ruchij.core.config.PureConfigReaders.given
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

final case class StorageConfiguration(videoFolder: String, imageFolder: String, otherVideoFolders: List[String])

object StorageConfiguration {
  given configReader: ConfigReader[StorageConfiguration] = deriveReader[StorageConfiguration]
}
