package com.ruchij.api.services.health.models

import com.ruchij.api.services.health.models.HealthCheck.{FileRepositoryCheck, HealthStatusDetails}
import com.ruchij.api.services.health.models.HealthStatus.Healthy
import com.ruchij.core.types.NamedToString

final case class HealthCheck(
  database: HealthStatusDetails,
  fileRepository: FileRepositoryCheck,
  keyValueStore: HealthStatusDetails,
  pubSub: HealthStatusDetails,
  spaRenderer: HealthStatusDetails,
  internetConnectivity: HealthStatusDetails
) extends NamedToString { self =>
  val isHealthy: Boolean =
    Set(self.database, self.keyValueStore, self.pubSub, self.spaRenderer, self.internetConnectivity)
      .forall(_.healthStatus == Healthy) && fileRepository.isHealthy
}

object HealthCheck {
  final case class HealthStatusDetails(durationInMs: Long, healthStatus: HealthStatus) extends NamedToString

  final case class FilePathCheck(filePath: String, healthStatusDetails: HealthStatusDetails) extends NamedToString

  final case class FileRepositoryCheck(
    imageFolder: FilePathCheck,
    videoFolder: FilePathCheck,
    otherVideoFolders: List[FilePathCheck]
  ) extends NamedToString { self =>
    val isHealthy: Boolean =
      Set(self.imageFolder, self.videoFolder)
        .concat(otherVideoFolders)
        .forall(_.healthStatusDetails.healthStatus == Healthy)
  }
}
