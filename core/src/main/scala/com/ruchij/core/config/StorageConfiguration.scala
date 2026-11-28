package com.ruchij.core.config

import com.ruchij.core.config.PureConfigReaders.given
import pureconfig.ConfigReader

final case class StorageConfiguration(videoFolder: String, imageFolder: String, otherVideoFolders: List[String])
    derives ConfigReader
