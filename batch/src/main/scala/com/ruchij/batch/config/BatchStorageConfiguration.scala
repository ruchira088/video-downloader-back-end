package com.ruchij.batch.config

import com.ruchij.core.config.StorageConfiguration

final case class BatchStorageConfiguration(videoFolder: String, imageFolder: String, otherVideoFolders: List[String])
    extends StorageConfiguration
