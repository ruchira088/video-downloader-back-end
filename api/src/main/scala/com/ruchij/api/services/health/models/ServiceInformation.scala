package com.ruchij.api.services.health.models

import cats.effect.Sync
import cats.implicits._
import com.eed3si9n.ruchij.api.BuildInfo
import com.ruchij.core.types.JodaClock
import org.joda.time.DateTime

import scala.util.Properties

final case class ServiceInformation(
  serviceName: String,
  serviceVersion: String,
  organization: String,
  scalaVersion: String,
  sbtVersion: String,
  javaVersion: String,
  currentTimestamp: DateTime,
  gitBranch: Option[String],
  gitCommit: Option[String],
  buildTimestamp: DateTime
)

object ServiceInformation {
  def create[F[_]: Sync: JodaClock]: F[ServiceInformation] =
    for {
      timestamp <- JodaClock[F].timestamp
      javaVersion <- Sync[F].delay(Properties.javaVersion)
    } yield
      ServiceInformation(
        BuildInfo.name,
        BuildInfo.version,
        BuildInfo.organization,
        BuildInfo.scalaVersion,
        BuildInfo.sbtVersion,
        javaVersion,
        timestamp,
        BuildInfo.gitBranch,
        BuildInfo.gitCommit,
        new DateTime(BuildInfo.buildTimestamp.toEpochMilli)
      )
}
