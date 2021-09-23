package com.ruchij.core.test.external.embedded

import cats.MonadError
import cats.effect.{Resource, Sync}
import cats.implicits._
import com.ruchij.core.config.{KafkaConfiguration, RedisConfiguration}
import com.ruchij.core.test.external.ExternalServiceProvider
import com.ruchij.core.test.external.embedded.EmbeddedExternalServiceProvider.availablePort
import com.ruchij.core.types.RandomGenerator
import com.ruchij.migration.config.DatabaseConfiguration
import io.github.embeddedkafka.EmbeddedKafkaConfig
import io.github.embeddedkafka.schemaregistry.{EmbeddedKafka, EmbeddedKafkaConfig => EmbeddedKafkaSchemaRegistryConfig}
import org.http4s.Uri
import org.http4s.Uri.Scheme
import redis.embedded.RedisServer

import java.net.ServerSocket
import java.util.UUID
import scala.util.Random

class EmbeddedExternalServiceProvider[F[+ _]: Sync]
    extends ExternalServiceProvider[F] {

  override val redisConfiguration: Resource[F, RedisConfiguration] =
    Resource
      .eval(EmbeddedExternalServiceProvider.availablePort[F](6300))
      .flatMap { port =>
        Resource
          .make {
            Sync[F]
              .delay(RedisServer.builder().port(port).build())
              .flatTap(redisServer => Sync[F].delay(redisServer.start()))
          } { redisServer =>
            Sync[F].delay(redisServer.stop())
          }
          .as(RedisConfiguration("localhost", port, None))
      }

  override val kafkaConfiguration: Resource[F, KafkaConfiguration] =
    for {
      kafkaPort <- Resource.eval(availablePort(EmbeddedKafkaConfig.defaultKafkaPort))
      zookeeperPort <- Resource.eval(availablePort(EmbeddedKafkaConfig.defaultZookeeperPort))
      schemaRegistryPort <- Resource.eval(availablePort(EmbeddedKafkaSchemaRegistryConfig.defaultSchemaRegistryPort))

      kafkaConfiguration = KafkaConfiguration(
        s"localhost:$kafkaPort",
        Uri(Some(Scheme.http), Some(Uri.Authority(port = Some(schemaRegistryPort))))
      )

      embeddedKafkaWithSR <- Resource.make(
        Sync[F]
          .delay(EmbeddedKafka.start()(EmbeddedKafkaSchemaRegistryConfig(kafkaPort, zookeeperPort, schemaRegistryPort)))
      ) { kafka =>
        Sync[F].delay(kafka.stop(false))
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
}

object EmbeddedExternalServiceProvider {
  def availablePort[F[+ _]: Sync](init: Int): F[Int] =
    RandomGenerator[F, Int](Random.nextInt() % 1000).generate
      .map(init + _)
      .flatMap { port =>
        MonadError[F, Throwable].handleErrorWith {
          Sync[F]
            .delay(new ServerSocket(port))
            .flatMap { serverSocket =>
              Sync[F].delay(serverSocket.close())
            }
            .as(port)
        } { _ =>
          availablePort[F](init)
        }
      }
}
