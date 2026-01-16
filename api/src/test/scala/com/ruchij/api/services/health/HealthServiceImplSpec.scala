package com.ruchij.api.services.health

import cats.effect._
import com.ruchij.api.services.health.models.HealthCheck
import com.ruchij.api.services.health.models.kv.HealthCheckKey.HealthCheckKeySpace
import com.ruchij.api.services.health.models.messaging.HealthCheckMessage
import com.ruchij.core.config.{SpaSiteRendererConfiguration, StorageConfiguration}
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.kv.{InMemoryKeyValueStore, KeySpacedKeyValueStore}
import com.ruchij.core.messaging.inmemory.Fs2PubSub
import com.ruchij.core.services.repository.InMemoryRepositoryService
import com.ruchij.core.services.video.YouTubeVideoDownloader
import com.ruchij.core.services.video.models.{VideoAnalysisResult, YTDownloaderProgress}
import com.ruchij.core.test.IOSupport.runIO
import fs2.Stream
import fs2.concurrent.Topic
import org.http4s.client.Client
import org.http4s.implicits.http4sLiteralsSyntax
import org.http4s.{HttpApp, Response, Status, Uri}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.ExecutionContext.Implicits.global

class HealthServiceImplSpec extends AnyFlatSpec with Matchers with MockFactory {
  "healthCheck" should "be healthy when all the services are healthy" in runHealthCheck(Status.Ok) {
    healthCheck: HealthCheck =>
      healthCheck.isHealthy mustBe true
  }

  it should "be unhealthy when any of the services are unhealthy" in runHealthCheck(Status.InternalServerError) {
    healthCheck: HealthCheck =>
      healthCheck.isHealthy mustBe false
  }

  private def runHealthCheck(httpStatus: Status)(assertion: HealthCheck => Unit): Unit =
    runIO {
      new EmbeddedCoreResourcesProvider[IO].transactor.use { implicit transactor =>
        for {
          healthCheckTopic <- Topic[IO, HealthCheckMessage]

          healthCheckPubSub = new Fs2PubSub[IO, HealthCheckMessage](healthCheckTopic)

          keyValueStore = new InMemoryKeyValueStore[IO]

          spaSiteRendererConfiguration = SpaSiteRendererConfiguration(uri"https://spa-renderer.video.home.ruchij.com")

          storageConfiguration = StorageConfiguration(
            videoFolder = "/video",
            imageFolder = "/images",
            otherVideoFolders = List("/a", "/b")
          )

          httpClient = Client.fromHttpApp[IO](HttpApp.liftF(IO.pure(Response[IO](status = httpStatus))))

          youTubeVideoDownloader = new YouTubeVideoDownloader[IO] {
            override def videoInformation(uri: Uri): IO[VideoAnalysisResult] =
              IO.raiseError(new NotImplementedError)

            override def downloadVideo(uri: Uri, pathWithoutExtension: String): Stream[IO, YTDownloaderProgress] =
              Stream.empty

            override val supportedSites: IO[Seq[String]] = IO.pure(Seq.empty)

            override val version: IO[String] = IO.pure("1.0.0")
          }

          repositoryService = new InMemoryRepositoryService[IO](new ConcurrentHashMap[String, List[Byte]]())

          healthService = new HealthServiceImpl[IO](
            repositoryService,
            new KeySpacedKeyValueStore(HealthCheckKeySpace, keyValueStore),
            healthCheckTopic.subscribeUnbounded,
            healthCheckPubSub,
            youTubeVideoDownloader,
            httpClient,
            storageConfiguration,
            spaSiteRendererConfiguration
          )

          healthCheck <- healthService.healthCheck
          _ <- IO.delay {
            withClue(s"Health check failed: $healthCheck") {
              assertion(healthCheck)
            }
          }
        } yield (): Unit
      }
    }
}
