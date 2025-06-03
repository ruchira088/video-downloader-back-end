package com.ruchij.api.services.health.models

import com.ruchij.api.services.health.models.HealthCheck.FileRepositoryCheck
import com.ruchij.api.services.health.models.HealthStatus.Healthy

final case class HealthCheck(
  database: HealthStatus,
  fileRepository: FileRepositoryCheck,
  keyValueStore: HealthStatus,
  pubSub: HealthStatus,
  spaRenderer: HealthStatus,
  internetConnectivity: HealthStatus
) { self =>
  val isHealthy: Boolean =
    Set(self.database, self.keyValueStore, self.pubSub, self.spaRenderer, self.internetConnectivity)
      .forall(_ == HealthStatus.Healthy) && fileRepository.isHealthy
}

object HealthCheck {
  final case class FilePathCheck(filePath: String, healthStatus: HealthStatus)

  final case class FileRepositoryCheck(
    imageFolder: FilePathCheck,
    videoFolder: FilePathCheck,
    otherVideoFolders: List[FilePathCheck]
  ) { self =>
    val isHealthy: Boolean =
      Set(self.imageFolder, self.videoFolder)
        .concat(otherVideoFolders)
        .forall(_.healthStatus == Healthy)
  }
}
