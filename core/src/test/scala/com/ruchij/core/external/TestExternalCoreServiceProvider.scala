package com.ruchij.core.external

import cats.effect.Resource
import com.ruchij.core.config.{KafkaConfiguration, SpaSiteRendererConfiguration}
import com.ruchij.core.external.TestExternalCoreServiceProvider.BranchName
import com.ruchij.migration.config.DatabaseConfiguration
import org.http4s.implicits.http4sLiteralsSyntax

class TestExternalCoreServiceProvider[F[_]](
  externalServiceProvider: ExternalCoreServiceProvider[F],
  environmentVariables: Map[String, String]
) extends ExternalCoreServiceProvider[F] {

  override val kafkaConfiguration: Resource[F, KafkaConfiguration] =
    externalServiceProvider.kafkaConfiguration

  override val databaseConfiguration: Resource[F, DatabaseConfiguration] =
    externalServiceProvider.databaseConfiguration

  private val isMasterBranch: Boolean =
    environmentVariables.get(BranchName).exists(_.equalsIgnoreCase("master"))

  override val spaSiteRendererConfiguration: Resource[F, SpaSiteRendererConfiguration] =
    Resource.pure {
      SpaSiteRendererConfiguration {
        if (isMasterBranch) uri"https://spa-renderer.video.home.ruchij.com"
        else uri"https://spa-renderer.dev.video.dev.ruchij.com"
      }
    }
}

object TestExternalCoreServiceProvider {
  private val BranchName: String = "GITHUB_REF_NAME"
}
