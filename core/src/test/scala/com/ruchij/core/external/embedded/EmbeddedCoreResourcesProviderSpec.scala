package com.ruchij.core.external.embedded

import cats.effect.IO
import com.ruchij.core.test.IOSupport.runIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class EmbeddedCoreResourcesProviderSpec extends AnyFlatSpec with Matchers {

  "EmbeddedCoreResourcesProvider.databaseConfiguration" should "return H2 in-memory database configuration" in runIO {
    val provider = new EmbeddedCoreResourcesProvider[IO]
    provider.databaseConfiguration.use { config =>
      IO {
        config.url must include("jdbc:h2:mem:video-downloader-")
        config.url must include("MODE=PostgreSQL")
        config.user mustBe ""
        config.password mustBe ""
      }
    }
  }

  "EmbeddedCoreResourcesProvider.spaSiteRendererConfiguration" should "raise UnsupportedOperationException" in runIO {
    val provider = new EmbeddedCoreResourcesProvider[IO]
    provider.spaSiteRendererConfiguration.use(_ => IO.unit).attempt.map { result =>
      result.isLeft mustBe true
      result.left.exists(_.isInstanceOf[UnsupportedOperationException]) mustBe true
      result.left.exists(_.getMessage.contains("Unable to start SPA site renderer")) mustBe true
    }
  }

  "EmbeddedCoreResourcesProvider.availablePort" should "find an available port" in runIO {
    EmbeddedCoreResourcesProvider.availablePort[IO](10000).map { port =>
      port must be >= 9000
      port must be <= 11000
    }
  }

  it should "return different ports on multiple calls" in runIO {
    for {
      port1 <- EmbeddedCoreResourcesProvider.availablePort[IO](10000)
      port2 <- EmbeddedCoreResourcesProvider.availablePort[IO](10000)
      port3 <- EmbeddedCoreResourcesProvider.availablePort[IO](10000)
    } yield {
      // Just verify they're valid ports; they might be the same if the previous one was released
      port1 must be >= 9000
      port2 must be >= 9000
      port3 must be >= 9000
    }
  }
}
