package com.ruchij.core.external.local

import cats.effect.IO
import com.ruchij.core.test.IOSupport.runIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class LocalCoreResourcesProviderSpec extends AnyFlatSpec with Matchers {

  "LocalCoreResourcesProvider.kafkaConfiguration" should "return local Kafka configuration" in runIO {
    val provider = new LocalCoreResourcesProvider[IO]
    provider.kafkaConfiguration.use { config =>
      IO {
        config.bootstrapServers mustBe "localhost:9092"
        config.schemaRegistry.toString mustBe "http://localhost:8081"
        config.label("test") mustBe "local-dev-test"
      }
    }
  }

  "LocalCoreResourcesProvider.databaseConfiguration" should "return local database configuration" in runIO {
    val provider = new LocalCoreResourcesProvider[IO]
    provider.databaseConfiguration.use { config =>
      IO {
        config.url mustBe "jdbc:postgresql://localhost:5432/video-downloader"
        config.user mustBe "admin"
        config.password mustBe "password"
      }
    }
  }

  "LocalCoreResourcesProvider.spaSiteRendererConfiguration" should "return local SPA renderer configuration" in runIO {
    val provider = new LocalCoreResourcesProvider[IO]
    provider.spaSiteRendererConfiguration.use { config =>
      IO {
        config.uri.toString mustBe "http://localhost:8000"
      }
    }
  }
}
