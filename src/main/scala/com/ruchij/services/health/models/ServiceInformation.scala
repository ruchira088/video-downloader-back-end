package com.ruchij.services.health.models

import cats.Applicative
import com.eed3si9n.ruchij.BuildInfo
import com.ruchij.circe.Encoders.dateTimeEncoder
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
  timestamp: DateTime
)

object ServiceInformation {
  implicit def serviceInformationEncoder[F[_]: Applicative]: EntityEncoder[F, ServiceInformation] =
    jsonEncoderOf[F, ServiceInformation]

  def apply(timestamp: DateTime): ServiceInformation =
    ServiceInformation(
      BuildInfo.name,
      BuildInfo.version,
      BuildInfo.organization,
      BuildInfo.scalaVersion,
      BuildInfo.sbtVersion,
      Properties.javaVersion,
      timestamp
    )
}
