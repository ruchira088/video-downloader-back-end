package com.ruchij.core.test

import cats.MonadError
import cats.effect.{Resource, Sync}
import cats.implicits._
import com.ruchij.core.config.{KafkaConfiguration, RedisConfiguration}
import com.ruchij.core.test.containers.{ContainerUtils, SchemaRegistryContainer}
import com.ruchij.core.types.RandomGenerator
import io.github.embeddedkafka.EmbeddedKafkaConfig
import io.github.embeddedkafka.schemaregistry.{EmbeddedKWithSR, EmbeddedKafka, EmbeddedKafkaConfig => EmbeddedKafkaSchemaRegistryConfig}
import org.http4s.Uri
import org.http4s.Uri.Scheme
import org.testcontainers.containers
import org.testcontainers.containers.{KafkaContainer, Network}
import org.testcontainers.utility.DockerImageName
import redis.embedded.RedisServer

import java.net.ServerSocket
import scala.util.Random

object Resources {

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

//  def startEmbeddedRedis[F[+ _]: Sync]: Resource[F, (RedisConfiguration, RedisServer)] =
//    Resource
//      .eval(availablePort[F](6300))
//      .map { port =>
//        RedisConfiguration("localhost", port, None) -> RedisServer.builder().port(port).build()
//      }
//      .flatTap {
//        case (_, redisServer) =>
//          Resource.make(Sync[F].delay(redisServer.start()))(_ => Sync[F].delay(redisServer.stop()))
//      }
//
//  def startEmbeddedKafkaAndSchemaRegistry[F[+ _]: Sync]: Resource[F, (KafkaConfiguration, EmbeddedKWithSR)] =
//    for {
//      kafkaPort <- Resource.eval(availablePort(EmbeddedKafkaConfig.defaultKafkaPort))
//      zookeeperPort <- Resource.eval(availablePort(EmbeddedKafkaConfig.defaultZookeeperPort))
//      schemaRegistryPort <- Resource.eval(availablePort(EmbeddedKafkaSchemaRegistryConfig.defaultSchemaRegistryPort))
//
//      kafkaConfiguration = KafkaConfiguration(
//        s"localhost:$kafkaPort",
//        Uri(Some(Scheme.http), Some(Uri.Authority(port = Some(schemaRegistryPort))))
//      )
//
//      embeddedKafkaWithSR <- Resource.make(
//        Sync[F]
//          .delay(EmbeddedKafka.start()(EmbeddedKafkaSchemaRegistryConfig(kafkaPort, zookeeperPort, schemaRegistryPort)))
//      ) { kafka =>
//        Sync[F].delay(kafka.stop(false))
//      }
//    } yield kafkaConfiguration -> embeddedKafkaWithSR

  def startEmbeddedRedis[F[+ _]: Sync]: Resource[F, (RedisConfiguration, RedisServer)] =
    Resource
      .eval(availablePort[F](6300))
      .map { port =>
        RedisConfiguration("localhost", port, None) -> RedisServer.builder().port(port).build()
      }
      .flatTap {
        case (_, redisServer) =>
          Resource.make(Sync[F].delay(redisServer.start()))(_ => Sync[F].delay(redisServer.stop()))
      }

  def startEmbeddedKafkaAndSchemaRegistry[F[+ _]: Sync]: Resource[F, KafkaConfiguration] =
    for {
      network <- Resource.eval(Sync[F].delay(Network.newNetwork()))

      kafkaContainer <-
        ContainerUtils.start[F, KafkaContainer] {
          new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:6.0.4"))
            .withNetwork(network)
            .withNetworkAliases("kafka")
        }

      schemaRegistryContainer <-
        ContainerUtils.start[F, SchemaRegistryContainer] {
          new SchemaRegistryContainer(network, "kafka")
        }

      kafkaBootstrapServers <- Resource.eval(Sync[F].delay(kafkaContainer.getBootstrapServers))
      schemaRegistry <- Resource.eval(schemaRegistryContainer.schemaRegistryUrl[F])
    }
    yield KafkaConfiguration(kafkaBootstrapServers, schemaRegistry)

}
