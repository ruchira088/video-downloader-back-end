package com.ruchij.api.test

import cats.effect.{ConcurrentEffect, ContextShift, Resource, Timer}
import com.ruchij.api.ApiApp
import com.ruchij.api.config.AuthenticationConfiguration.{HashedPassword, PasswordAuthenticationConfiguration}
import com.ruchij.api.config.{ApiServiceConfiguration, HttpConfiguration}
import com.ruchij.core.config.{ApplicationInformation, DownloadConfiguration}
import com.ruchij.core.test.{DoobieProvider, Resources}
import org.http4s.HttpApp

import scala.concurrent.duration._
import scala.language.postfixOps

object HttpTestResource {
  val DownloadConfig: DownloadConfiguration = DownloadConfiguration("./videos", "./images")

  val ApplicationInfo: ApplicationInformation =
    ApplicationInformation("localhost", Some("N/A"), Some("N/A"), None)

  val HttpConfig: HttpConfiguration = HttpConfiguration("localhost", 8000)

  val PasswordAuthenticationConfig: PasswordAuthenticationConfiguration =
    PasswordAuthenticationConfiguration(
      HashedPassword("$2a$10$m5CQAirrrJKRqG3oalNSU.TUOn56v88isxMbNPi8cXXI35gY20hO."), // The password is "top-secret"
      30 days
    )

  def apply[F[+ _]: ConcurrentEffect: Timer: ContextShift]: Resource[F, (ApiServiceConfiguration, HttpApp[F])] =
    for {
      (redisConfiguration, _) <- Resources.startEmbeddedRedis[F]
      (kafkaConfiguration, _) <- Resources.startEmbeddedKafkaAndSchemaRegistry[F]

      databaseConfiguration <- Resource.liftF(DoobieProvider.uniqueH2InMemoryDatabaseConfiguration[F])

      apiServiceConfiguration =
        ApiServiceConfiguration(
          HttpConfig,
          DownloadConfig,
          databaseConfiguration,
          redisConfiguration,
          PasswordAuthenticationConfig,
          kafkaConfiguration,
          ApplicationInfo
        )

      httpApp <- ApiApp.program[F](apiServiceConfiguration)
    }
    yield apiServiceConfiguration -> httpApp

}
