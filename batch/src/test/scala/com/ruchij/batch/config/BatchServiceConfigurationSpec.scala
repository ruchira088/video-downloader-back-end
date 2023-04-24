package com.ruchij.batch.config

import cats.effect.IO
import com.ruchij.core.config.{KafkaConfiguration, SpaSiteRendererConfiguration}
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.migration.config.DatabaseConfiguration
import org.http4s.implicits.http4sLiteralsSyntax
import org.joda.time.LocalTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import pureconfig.ConfigSource

class BatchServiceConfigurationSpec extends AnyFlatSpec with Matchers {

  "BatchServiceConfiguration.parse" should "parse the ConfigObjectSource" in runIO {
    val configSource =
      s"""
        worker-configuration {
          max-concurrent-downloads = 10
          max-concurrent-downloads = $${?MAX_CONCURRENT_DOWNLOADS}

          start-time = "00:00"
          start-time = $${?START_TIME}

          end-time = "00:00"
          end-time = $${?END_TIME}
        }

        storage-configuration {
          video-folder = "./videos"
          video-folder = $${?VIDEO_FOLDER}

          image-folder = "./images"
          image-folder = $${?IMAGE_FOLDER}

          other-video-folders = "./video-folder-1;./video-folder-2"
          other-video-folders = $${?OTHER_VIDEO_FOLDERS}
        }

        database-configuration {
          url = "jdbc:h2:mem:video-downloader;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
          url = $${?DATABASE_URL}

          user = "my-user"
          user = $${?DATABASE_USER}

          password = "my-password"
          password = $${?DATABASE_PASSWORD}
        }

        kafka-configuration {
          prefix = "local"
          prefix = $${?KAFKA_PREFIX}

          bootstrap-servers = "kafka-cluster:9092"

          schema-registry = "http://kafka-cluster:8081"
        }

        spa-site-renderer-configuration {
          uri = "http://spa-renderer-service:8000"
          uri = $${?SPA_SITE_RENDERER}
        }
      """

    val expectedBatchServiceConfiguration =
      BatchServiceConfiguration(
        BatchStorageConfiguration("./videos", "./images", List("./video-folder-1", "./video-folder-2")),
        WorkerConfiguration(10, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT),
        DatabaseConfiguration("jdbc:h2:mem:video-downloader;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", "my-user", "my-password"),
        KafkaConfiguration("local", "kafka-cluster:9092", uri"http://kafka-cluster:8081"),
        SpaSiteRendererConfiguration(uri"http://spa-renderer-service:8000"),
      )

    BatchServiceConfiguration.parse[IO](ConfigSource.string(configSource)).flatMap {
      batchServiceConfiguration =>
        IO.delay {
          batchServiceConfiguration mustBe expectedBatchServiceConfiguration
        }
    }
  }

}
