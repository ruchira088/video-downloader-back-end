package com.ruchij.api.services.health

import cats.effect._
import cats.effect.std.Dispatcher
import com.ruchij.api.external.ApiResourcesProvider
import com.ruchij.api.external.containers.ContainerApiResourcesProvider
import com.ruchij.api.services.health.models.kv.HealthCheckKey.HealthCheckKeySpace
import com.ruchij.api.services.health.models.messaging.HealthCheckMessage
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.kv.{KeySpacedKeyValueStore, RedisKeyValueStore}
import com.ruchij.core.messaging.kafka.KafkaPubSub
import com.ruchij.core.services.cli.CliCommandRunnerImpl
import com.ruchij.core.services.repository.InMemoryRepositoryService
import com.ruchij.core.services.video.YouTubeVideoDownloaderImpl
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.{JodaClock, RandomGenerator}
import fs2.concurrent.Topic
import org.http4s.jdkhttpclient.JdkHttpClient
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.net.http.HttpClient
import java.net.http.HttpClient.Redirect
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.ExecutionContext.Implicits.global

class HealthServiceImplSpec extends AnyFlatSpec with Matchers {

  "healthCheck" should "be healthy when all the services are healthy" in runIO {
    val apiResourcesProvider: ApiResourcesProvider[IO] = new ContainerApiResourcesProvider[IO]

    val healthServiceResource = for {
      redisConfiguration <- apiResourcesProvider.redisConfiguration
      redisKeyValueStore <- RedisKeyValueStore.create[IO](redisConfiguration)

      kafkaConfiguration <- apiResourcesProvider.kafkaConfiguration
      healthCheckPubSub <- KafkaPubSub[IO, HealthCheckMessage](kafkaConfiguration)
      healthCheckTopic <- Resource.eval(Topic[IO, HealthCheckMessage])

      _ <- Resource.eval {
        Concurrent[IO].start {
          healthCheckTopic
            .publish {
              healthCheckPubSub.subscribe("health").evalMap { committableMessage =>
                healthCheckPubSub.commit(List(committableMessage)).as(committableMessage.value)
              }
            }
            .compile
            .drain
        }
      }

      spaSiteRendererConfiguration <- apiResourcesProvider.spaSiteRendererConfiguration

      dispatcher <- Dispatcher.parallel[IO]
      cliCommandRunner = new CliCommandRunnerImpl[IO](dispatcher)

      javaHttpClient <- Resource.eval {
        Sync[IO].delay {
          HttpClient.newBuilder()
            .followRedirects(Redirect.NORMAL)
            .build()
        }
      }
      httpClient = JdkHttpClient[IO](javaHttpClient)
      youTubeVideoDownloader = new YouTubeVideoDownloaderImpl[IO](cliCommandRunner, httpClient)

      repositoryService = new InMemoryRepositoryService[IO](new ConcurrentHashMap[String, List[Byte]]())



      transactor <- apiResourcesProvider.transactor

      storageConfiguration = StorageConfiguration(
        videoFolder = "/video",
        imageFolder = "/images",
        otherVideoFolders = List("/a", "/b")
      )



      healthServiceImpl = new HealthServiceImpl[IO](
        repositoryService,
        new KeySpacedKeyValueStore(HealthCheckKeySpace, redisKeyValueStore),
        healthCheckTopic.subscribeUnbounded,
        healthCheckPubSub,
        youTubeVideoDownloader,
        httpClient,
        storageConfiguration,
        spaSiteRendererConfiguration
      )(Async[IO], JodaClock[IO], RandomGenerator[IO, UUID], transactor)

    } yield healthServiceImpl

    healthServiceResource.use {
      healthService =>
        healthService.healthCheck.flatMap {
          healthCheck => IO.delay {
            withClue(s"Health check failed: $healthCheck") {
              healthCheck.isHealthy mustBe true
            }
          }
        }
    }
  }
}
