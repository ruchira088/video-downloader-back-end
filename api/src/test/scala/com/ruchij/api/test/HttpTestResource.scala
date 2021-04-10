package com.ruchij.api.test

import cats.effect.{ConcurrentEffect, ContextShift, Resource, Timer}
import com.ruchij.api.ApiApp
import com.ruchij.api.config.AuthenticationConfiguration.{HashedPassword, PasswordAuthenticationConfiguration}
import com.ruchij.api.config.{ApiServiceConfiguration, HttpConfiguration}
import com.ruchij.core.config.models.ApplicationMode
import com.ruchij.core.config.{ApplicationInformation, DownloadConfiguration, KafkaConfiguration}
import com.ruchij.core.test.{DoobieProvider, Resources}
import org.http4s.{HttpApp, Uri}

import scala.concurrent.duration._
import scala.language.postfixOps

object HttpTestResource {
  val DownloadConfig: DownloadConfiguration = DownloadConfiguration("./videos", "./images")

  val ApplicationInfo: ApplicationInformation =
    ApplicationInformation(ApplicationMode.Test, "localhost", Some("N/A"), Some("N/A"), None)

  val HttpConfig: HttpConfiguration = HttpConfiguration("localhost", 8000)

  val PasswordAuthenticationConfig: PasswordAuthenticationConfiguration =
    PasswordAuthenticationConfiguration(
      HashedPassword("$2a$10$m5CQAirrrJKRqG3oalNSU.TUOn56v88isxMbNPi8cXXI35gY20hO."), // The password is "top-secret"
      30 days
    )

  val KafkaConfig: KafkaConfiguration = KafkaConfiguration("N/A", Uri())

  def apply[F[+ _]: ConcurrentEffect: Timer: ContextShift]: Resource[F, (ApiServiceConfiguration, HttpApp[F])] =
    for {
      (redisConfiguration, _) <- Resources.startEmbeddedRedis[F]
//      (kafkaConfiguration, _) <- Resources.startEmbeddedKafkaAndSchemaRegistry[F]

      databaseConfiguration <- Resource.eval(DoobieProvider.uniqueH2InMemoryDatabaseConfiguration[F])

      apiServiceConfiguration =
        ApiServiceConfiguration(
          HttpConfig,
          DownloadConfig,
          databaseConfiguration,
          redisConfiguration,
          PasswordAuthenticationConfig,
          KafkaConfig,
          ApplicationInfo
        )

      httpApp <- ApiApp.program[F](apiServiceConfiguration)
    }
    yield apiServiceConfiguration -> httpApp

}
