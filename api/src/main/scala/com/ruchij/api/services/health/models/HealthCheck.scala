package com.ruchij.api.services.health.models

import com.ruchij.api.services.health.models.HealthCheck.{FileRepositoryCheck, HealthStatusDetails}
import com.ruchij.api.services.health.models.HealthStatus.Healthy

final case class HealthCheck(
  database: HealthStatusDetails,
  fileRepository: FileRepositoryCheck,
  keyValueStore: HealthStatusDetails,
  pubSub: HealthStatusDetails,
  spaRenderer: HealthStatusDetails,
  internetConnectivity: HealthStatusDetails
) { self =>
  val isHealthy: Boolean =
    Set(self.database, self.keyValueStore, self.pubSub, self.spaRenderer, self.internetConnectivity)
      .forall(_.healthStatus == Healthy) && fileRepository.isHealthy

  override def toString: String =
    s"""HealthCheck(
       |  database=$database,
       |  fileRepository=$fileRepository,
       |  keyValueStore=$keyValueStore,
       |  pubSub=$pubSub,
       |  spaRenderer=$spaRenderer,
       |  internetConnectivity=$internetConnectivity
       |)
      |""".stripMargin
}

object HealthCheck {
  final case class HealthStatusDetails(durationInMs: Long, healthStatus: HealthStatus)

  final case class FilePathCheck(filePath: String, healthStatusDetails: HealthStatusDetails)

  final case class FileRepositoryCheck(
    imageFolder: FilePathCheck,
    videoFolder: FilePathCheck,
    otherVideoFolders: List[FilePathCheck]
  ) { self =>
    val isHealthy: Boolean =
      Set(self.imageFolder, self.videoFolder)
        .concat(otherVideoFolders)
        .forall(_.healthStatusDetails.healthStatus == Healthy)
  }
}
