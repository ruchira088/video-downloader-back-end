package com.ruchij.core.external.embedded

import cats.MonadError
import cats.effect.{MonadCancelThrow, Resource, Sync}
import cats.implicits._
import com.ruchij.core.config.{KafkaConfiguration, SpaSiteRendererConfiguration}
import com.ruchij.core.external.CoreResourcesProvider
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider.availablePort
import com.ruchij.core.types.RandomGenerator
import com.ruchij.migration.config.DatabaseConfiguration
import io.github.embeddedkafka.EmbeddedKafkaConfig
import io.github.embeddedkafka.schemaregistry.{EmbeddedKafka, EmbeddedKafkaConfig => EmbeddedKafkaSchemaRegistryConfig}
import org.http4s.Uri
import org.http4s.Uri.Scheme

import java.net.ServerSocket
import java.util.UUID
import scala.util.Random

class EmbeddedCoreResourcesProvider[F[_]: Sync] extends CoreResourcesProvider[F] {

  override val kafkaConfiguration: Resource[F, KafkaConfiguration] =
    for {
      kafkaPort <- Resource.eval(availablePort(EmbeddedKafkaConfig.defaultKafkaPort))
      schemaRegistryPort <- Resource.eval(availablePort(EmbeddedKafkaSchemaRegistryConfig.defaultSchemaRegistryPort))

      kafkaConfiguration = KafkaConfiguration(
        "local-dev",
        s"localhost:$kafkaPort",
        Uri(Some(Scheme.http), Some(Uri.Authority(port = Some(schemaRegistryPort))))
      )

      embeddedKafkaWithSR <- Resource.make(
        Sync[F]
          .blocking(
            EmbeddedKafka.start()(
              EmbeddedKafkaSchemaRegistryConfig(kafkaPort = kafkaPort, schemaRegistryPort = schemaRegistryPort)
            )
          )
      ) { kafka =>
        Sync[F].blocking(kafka.stop(false))
      }
    } yield kafkaConfiguration

  private def h2InMemoryDatabaseConfiguration(databaseName: String): DatabaseConfiguration =
    DatabaseConfiguration(
      s"jdbc:h2:mem:video-downloader-$databaseName;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
      "",
      ""
    )

  override val databaseConfiguration: Resource[F, DatabaseConfiguration] =
    Resource.eval {
      RandomGenerator[F, UUID].generate
        .map(uuid => h2InMemoryDatabaseConfiguration(uuid.toString.take(8)))
    }
  override val spaSiteRendererConfiguration: Resource[F, SpaSiteRendererConfiguration] =
    Resource.raiseError[F, SpaSiteRendererConfiguration, Throwable] {
      new UnsupportedOperationException("Unable to start SPA site renderer in embedded external services")
    }
}

object EmbeddedCoreResourcesProvider {
  def availablePort[F[_]: Sync](init: Int): F[Int] =
    RandomGenerator[F, Int](Random.nextInt() % 1000).generate
      .map(init + _)
      .flatMap { port =>
        MonadError[F, Throwable].handleErrorWith {
          MonadCancelThrow[F].uncancelable { _ =>
            Sync[F]
              .blocking(new ServerSocket(port))
              .flatMap { serverSocket =>
                Sync[F].blocking(serverSocket.close())
              }
              .as(port)
          }
        }(_ => availablePort[F](init))
      }
}
