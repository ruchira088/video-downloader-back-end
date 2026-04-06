package com.ruchij.api.services.health

import cats.effect._
import com.ruchij.api.services.health.models.{HealthCheck, HealthStatus}
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
  "healthCheck" should "be healthy when all the services are healthy" in
    runHealthCheck(proxiedHttpStatus = Status.Ok, directHttpStatus = Status.Ok) {
      healthCheck: HealthCheck =>
        healthCheck.isHealthy mustBe true
    }

  it should "be unhealthy when any of the services are unhealthy" in
    runHealthCheck(proxiedHttpStatus = Status.InternalServerError, directHttpStatus = Status.InternalServerError) {
      healthCheck: HealthCheck =>
        healthCheck.isHealthy mustBe false
    }

  it should "be unhealthy when proxied client fails but direct client succeeds" in
    runHealthCheck(proxiedHttpStatus = Status.InternalServerError, directHttpStatus = Status.Ok) {
      healthCheck: HealthCheck =>
        healthCheck.isHealthy mustBe false
        healthCheck.internetConnectivity.healthStatus mustBe HealthStatus.Unhealthy
    }

  it should "be unhealthy when direct client fails but proxied client succeeds" in
    runHealthCheck(proxiedHttpStatus = Status.Ok, directHttpStatus = Status.InternalServerError) {
      healthCheck: HealthCheck =>
        healthCheck.isHealthy mustBe false
        healthCheck.spaRenderer.healthStatus mustBe HealthStatus.Unhealthy
    }

  it should "report healthy internet connectivity when proxied client returns Ok" in
    runHealthCheck(proxiedHttpStatus = Status.Ok, directHttpStatus = Status.Ok) {
      healthCheck: HealthCheck =>
        healthCheck.internetConnectivity.healthStatus mustBe HealthStatus.Healthy
    }

  it should "report healthy spa renderer when direct client returns Ok" in
    runHealthCheck(proxiedHttpStatus = Status.Ok, directHttpStatus = Status.Ok) {
      healthCheck: HealthCheck =>
        healthCheck.spaRenderer.healthStatus mustBe HealthStatus.Healthy
    }

  private def runHealthCheck(proxiedHttpStatus: Status, directHttpStatus: Status)(assertion: HealthCheck => Unit): Unit =
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

          proxiedHttpClient = Client.fromHttpApp[IO](HttpApp.liftF(IO.pure(Response[IO](status = proxiedHttpStatus))))
          httpClient = Client.fromHttpApp[IO](HttpApp.liftF(IO.pure(Response[IO](status = directHttpStatus))))

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
            proxiedHttpClient,
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
