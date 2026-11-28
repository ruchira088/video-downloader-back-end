package com.ruchij.core.config

import com.ruchij.core.config.PureConfigReaders.*
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

final case class StorageConfiguration(videoFolder: String, imageFolder: String, otherVideoFolders: List[String])

object StorageConfiguration {
  implicit val configReader: ConfigReader[StorageConfiguration] = deriveReader[StorageConfiguration]
}
