package com.ruchij.services.health.models

import java.util.concurrent.TimeUnit

import cats.Applicative
import cats.effect.{Clock, Sync}
import cats.implicits._
import com.eed3si9n.ruchij.BuildInfo
import com.ruchij.circe.Encoders.dateTimeEncoder
import com.ruchij.config.ApplicationInformation
import io.circe.generic.auto._
import org.http4s.EntityEncoder
import org.http4s.circe.jsonEncoderOf
import org.joda.time.DateTime

import scala.util.Properties

case class ServiceInformation(
  serviceName: String,
  serviceVersion: String,
  organization: String,
  scalaVersion: String,
  sbtVersion: String,
  javaVersion: String,
  currentTimestamp: DateTime,
  gitBranch: Option[String],
  gitCommit: Option[String],
  buildTimestamp: Option[DateTime]
)

object ServiceInformation {
  implicit def serviceInformationEncoder[F[_]: Applicative]: EntityEncoder[F, ServiceInformation] =
    jsonEncoderOf[F, ServiceInformation]

  def create[F[_]: Sync: Clock](applicationInformation: ApplicationInformation): F[ServiceInformation] =
    for {
      timestamp <- Clock[F].realTime(TimeUnit.MILLISECONDS)
      javaVersion <- Sync[F].delay(Properties.javaVersion)

    } yield
      ServiceInformation(
        BuildInfo.name,
        BuildInfo.version,
        BuildInfo.organization,
        BuildInfo.scalaVersion,
        BuildInfo.sbtVersion,
        javaVersion,
        new DateTime(timestamp),
        applicationInformation.gitBranch,
        applicationInformation.gitCommit,
        applicationInformation.buildTimestamp
      )
}
