package com.ruchij.api.services.health.models

import cats.effect.Sync
import cats.implicits._
import com.eed3si9n.ruchij.api.BuildInfo
import com.ruchij.core.types.Clock

import java.time.Instant
import scala.util.Properties

final case class ServiceInformation(
  serviceName: String,
  organization: String,
  scalaVersion: String,
  sbtVersion: String,
  javaVersion: String,
  `yt-dlpVersion`: String,
  currentTimestamp: Instant,
  gitBranch: Option[String],
  gitCommit: Option[String],
  buildTimestamp: Instant
)

object ServiceInformation {
  def create[F[_]: Sync: Clock](ytDlpVersion: String): F[ServiceInformation] =
    for {
      timestamp <- Clock[F].timestamp
      javaVersion <- Sync[F].delay(Properties.javaVersion)
    } yield
      ServiceInformation(
        BuildInfo.name,
        BuildInfo.organization,
        BuildInfo.scalaVersion,
        BuildInfo.sbtVersion,
        javaVersion,
        ytDlpVersion,
        timestamp,
        BuildInfo.gitBranch,
        BuildInfo.gitCommit,
        BuildInfo.buildTimestamp
      )
}
