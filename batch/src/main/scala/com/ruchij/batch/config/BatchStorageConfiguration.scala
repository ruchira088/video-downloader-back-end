package com.ruchij.batch.config

import com.ruchij.core.config.StorageConfiguration

case class BatchStorageConfiguration(videoFolder: String, imageFolder: String, otherVideoFolders: List[String])
    extends StorageConfiguration
