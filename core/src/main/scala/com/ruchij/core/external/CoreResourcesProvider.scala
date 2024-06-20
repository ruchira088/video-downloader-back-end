package com.ruchij.core.external

import cats.effect.{Async, Resource}
import cats.~>
import com.ruchij.core.config.{KafkaConfiguration, SpaSiteRendererConfiguration}
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.migration.MigrationApp
import com.ruchij.migration.config.{AdminConfiguration, DatabaseConfiguration, MigrationServiceConfiguration}
import doobie.free.connection.ConnectionIO

import scala.concurrent.ExecutionContext

trait CoreResourcesProvider[F[_]] {
  val kafkaConfiguration: Resource[F, KafkaConfiguration]

  val databaseConfiguration: Resource[F, DatabaseConfiguration]

  val spaSiteRendererConfiguration: Resource[F, SpaSiteRendererConfiguration]
}

object CoreResourcesProvider {
  val HashedAdminPassword = "$2a$10$m5CQAirrrJKRqG3oalNSU.TUOn56v88isxMbNPi8cXXI35gY20hO." // The password is "top-secret"

  def migrationServiceConfiguration(databaseConfiguration: DatabaseConfiguration): MigrationServiceConfiguration =
    MigrationServiceConfiguration(databaseConfiguration, AdminConfiguration(HashedAdminPassword))

  def transactor[F[_]: Async](
    databaseConfig: DatabaseConfiguration
  )(implicit executionContext: ExecutionContext): Resource[F, ConnectionIO ~> F] =
    for {
      hikariTransactor <- DoobieTransactor
        .create[F](databaseConfig, executionContext)

      migrationResult <- Resource.eval(MigrationApp.migration(migrationServiceConfiguration(databaseConfig)))
    } yield hikariTransactor.trans

  implicit class ExternalServiceProviderOps[F[_]](externalServiceProvider: CoreResourcesProvider[F]) {
    def transactor(
      implicit async: Async[F],
      executionContext: ExecutionContext
    ): Resource[F, ConnectionIO ~> F] =
      externalServiceProvider.databaseConfiguration
        .flatMap(databaseConfig => CoreResourcesProvider.transactor(databaseConfig))
  }

}
