package com.ruchij.core.test.external

import cats.effect.{Async, Resource}
import cats.~>
import com.ruchij.core.config.{KafkaConfiguration, RedisConfiguration}
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.migration.MigrationApp
import com.ruchij.migration.config.{AdminConfiguration, DatabaseConfiguration, MigrationServiceConfiguration}
import doobie.free.connection.ConnectionIO

import scala.concurrent.ExecutionContext

trait ExternalServiceProvider[F[_]] {
  val redisConfiguration: Resource[F, RedisConfiguration]

  val kafkaConfiguration: Resource[F, KafkaConfiguration]

  val databaseConfiguration: Resource[F, DatabaseConfiguration]
}

object ExternalServiceProvider {
  val HashedAdminPassword = "$2a$10$m5CQAirrrJKRqG3oalNSU.TUOn56v88isxMbNPi8cXXI35gY20hO." // The password is "top-secret"

  def transactor[F[_]: Async](
    databaseConfig: DatabaseConfiguration
  )(implicit executionContext: ExecutionContext): Resource[F, ConnectionIO ~> F] =
    for {
      hikariTransactor <- DoobieTransactor
        .create[F](databaseConfig, executionContext)

      migrationResult <-
        Resource.eval {
          MigrationApp.migration(MigrationServiceConfiguration(databaseConfig, AdminConfiguration(HashedAdminPassword)))
        }
    } yield hikariTransactor.trans

  implicit class ExternalServiceProviderOps[F[_]](externalServiceProvider: ExternalServiceProvider[F]) {
    def transactor(
      implicit async: Async[F],
      executionContext: ExecutionContext
    ): Resource[F, ConnectionIO ~> F] =
      externalServiceProvider.databaseConfiguration
        .flatMap(databaseConfig => ExternalServiceProvider.transactor(databaseConfig))
  }

}
