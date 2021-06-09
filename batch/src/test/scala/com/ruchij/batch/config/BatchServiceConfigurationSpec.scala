package com.ruchij.batch.config

import com.ruchij.core.config.{ApplicationInformation, KafkaConfiguration}
import com.ruchij.migration.config.DatabaseConfiguration
import org.http4s.implicits.http4sLiteralsSyntax
import org.joda.time.LocalTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import pureconfig.ConfigSource

class BatchServiceConfigurationSpec extends AnyFlatSpec with Matchers {

  "Parsing the configuration file" should "return expected batch service configuration" in {
    val configSource =
      """
        worker-configuration {
          max-concurrent-downloads = 10
          max-concurrent-downloads = ${?MAX_CONCURRENT_DOWNLOADS}

          start-time = "00:00"
          start-time = ${?START_TIME}

          end-time = "00:00"
          end-time = ${?END_TIME}
        }

        storage-configuration {
          video-folder = "./videos"
          video-folder = ${?VIDEO_FOLDER}

          image-folder = "./images"
          image-folder = ${?IMAGE_FOLDER}

          other-video-folders = "./video-folder-1;./video-folder-2"
          other-video-folders = ${?OTHER_VIDEO_FOLDERS}
        }

        database-configuration {
          url = "jdbc:h2:mem:video-downloader;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
          url = ${?DATABASE_URL}

          user = "my-user"
          user = ${?DATABASE_USER}

          password = "my-password"
          password = ${?DATABASE_PASSWORD}
        }

        kafka-configuration {
          bootstrap-servers = "kafka-cluster:9092"

          schema-registry = "http://kafka-cluster:8081"
        }

        application-information {
          instance-id = "localhost"
          instance-id = ${?HOSTNAME}
          instance-id = ${?INSTANCE_ID}

          git-branch = ${?GIT_BRANCH}
          git-commit = ${?GIT_COMMIT}
          build-timestamp = ${?BUILD_TIMESTAMP}
        }
      """

    BatchServiceConfiguration.parse[Either[Throwable, *]](ConfigSource.string(configSource)) mustBe
      Right {
        BatchServiceConfiguration(
          BatchStorageConfiguration("./videos", "./images", List("./video-folder-1", "./video-folder-2")),
          WorkerConfiguration(10, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT),
          DatabaseConfiguration("jdbc:h2:mem:video-downloader;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false", "my-user", "my-password"),
          KafkaConfiguration("kafka-cluster:9092", uri"http://kafka-cluster:8081"),
          ApplicationInformation("localhost", None, None, None)
        )
      }
  }

}
