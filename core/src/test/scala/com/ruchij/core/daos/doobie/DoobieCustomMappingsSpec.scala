package com.ruchij.core.daos.doobie

import cats.effect.IO
import com.ruchij.core.daos.scheduling.models.SchedulingStatus
import com.ruchij.core.daos.videometadata.models.VideoSite
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.migration.config.DatabaseConfiguration
import doobie.implicits._
import org.http4s.{MediaType, Uri}
import org.joda.time.DateTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.nio.file.{Path, Paths}
import scala.concurrent.duration._

class DoobieCustomMappingsSpec extends AnyFlatSpec with Matchers {

  import DoobieCustomMappings._

  private val dbConfig = DatabaseConfiguration(
    s"jdbc:h2:mem:test-custom-mappings-${System.currentTimeMillis()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
    "",
    ""
  )

  "videoSitePut and videoSiteGet" should "round-trip VideoSite values" in runIO {
    DoobieTransactor.create[IO](dbConfig).use { transactor =>
      val testVideoSite: VideoSite = VideoSite.YTDownloaderSite("youtube")
      for {
        _ <- sql"CREATE TABLE video_site_test (site VARCHAR(255))".update.run.transact(transactor)
        _ <- sql"INSERT INTO video_site_test VALUES (${testVideoSite: VideoSite})".update.run.transact(transactor)
        result <- sql"SELECT site FROM video_site_test".query[VideoSite].unique.transact(transactor)
      } yield {
        result.name mustBe testVideoSite.name
      }
    }
  }

  "enumPut and enumGet" should "round-trip enum values" in runIO {
    DoobieTransactor.create[IO](dbConfig).use { transactor =>
      for {
        _ <- sql"CREATE TABLE enum_test (status VARCHAR(255))".update.run.transact(transactor)
        _ <- sql"INSERT INTO enum_test VALUES (${SchedulingStatus.Completed: SchedulingStatus})".update.run.transact(transactor)
        result <- sql"SELECT status FROM enum_test".query[SchedulingStatus].unique.transact(transactor)
      } yield {
        result mustBe SchedulingStatus.Completed
      }
    }
  }

  "uriPut and uriGet" should "round-trip Uri values" in runIO {
    DoobieTransactor.create[IO](dbConfig).use { transactor =>
      val testUri = Uri.unsafeFromString("https://example.com/path?query=value")
      for {
        _ <- sql"CREATE TABLE uri_test (uri VARCHAR(255))".update.run.transact(transactor)
        _ <- sql"INSERT INTO uri_test VALUES (${testUri: Uri})".update.run.transact(transactor)
        result <- sql"SELECT uri FROM uri_test".query[Uri].unique.transact(transactor)
      } yield {
        result mustBe testUri
      }
    }
  }

  "finiteDurationPut and finiteDurationGet" should "round-trip FiniteDuration values" in runIO {
    DoobieTransactor.create[IO](dbConfig).use { transactor =>
      val testDuration = 5.seconds
      for {
        _ <- sql"CREATE TABLE duration_test (duration BIGINT)".update.run.transact(transactor)
        _ <- sql"INSERT INTO duration_test VALUES (${testDuration: FiniteDuration})".update.run.transact(transactor)
        result <- sql"SELECT duration FROM duration_test".query[FiniteDuration].unique.transact(transactor)
      } yield {
        result mustBe testDuration
      }
    }
  }

  "dateTimePut and dateTimeGet" should "round-trip DateTime values" in runIO {
    DoobieTransactor.create[IO](dbConfig).use { transactor =>
      val testDateTime = new DateTime(2024, 1, 15, 10, 30, 0, 0)
      for {
        _ <- sql"CREATE TABLE datetime_test (dt TIMESTAMP)".update.run.transact(transactor)
        _ <- sql"INSERT INTO datetime_test VALUES (${testDateTime: DateTime})".update.run.transact(transactor)
        result <- sql"SELECT dt FROM datetime_test".query[DateTime].unique.transact(transactor)
      } yield {
        result.getMillis mustBe testDateTime.getMillis
      }
    }
  }

  "pathPut and pathGet" should "round-trip Path values" in runIO {
    DoobieTransactor.create[IO](dbConfig).use { transactor =>
      val testPath = Paths.get("/tmp/test/file.txt")
      for {
        _ <- sql"CREATE TABLE path_test (path VARCHAR(255))".update.run.transact(transactor)
        _ <- sql"INSERT INTO path_test VALUES (${testPath: Path})".update.run.transact(transactor)
        result <- sql"SELECT path FROM path_test".query[Path].unique.transact(transactor)
      } yield {
        result mustBe testPath.toAbsolutePath
      }
    }
  }

  "mediaTypePut and mediaTypeGet" should "round-trip MediaType values" in runIO {
    DoobieTransactor.create[IO](dbConfig).use { transactor =>
      val testMediaType = MediaType.video.mp4
      for {
        _ <- sql"CREATE TABLE media_type_test (media_type VARCHAR(255))".update.run.transact(transactor)
        _ <- sql"INSERT INTO media_type_test VALUES (${testMediaType: MediaType})".update.run.transact(transactor)
        result <- sql"SELECT media_type FROM media_type_test".query[MediaType].unique.transact(transactor)
      } yield {
        result mustBe testMediaType
      }
    }
  }

  "enumGet" should "fail for invalid enum value" in runIO {
    DoobieTransactor.create[IO](dbConfig).use { transactor =>
      for {
        _ <- sql"CREATE TABLE enum_error_test (status VARCHAR(255))".update.run.transact(transactor)
        _ <- sql"INSERT INTO enum_error_test VALUES ('invalid-status')".update.run.transact(transactor)
        result <- sql"SELECT status FROM enum_error_test".query[SchedulingStatus].unique.transact(transactor).attempt
      } yield {
        result.isLeft mustBe true
      }
    }
  }

  "uriGet" should "fail for invalid URI" in runIO {
    DoobieTransactor.create[IO](dbConfig).use { transactor =>
      for {
        _ <- sql"CREATE TABLE uri_error_test (uri VARCHAR(255))".update.run.transact(transactor)
        _ <- sql"INSERT INTO uri_error_test VALUES ('not a valid uri with spaces and ::: stuff')".update.run.transact(transactor)
        result <- sql"SELECT uri FROM uri_error_test".query[Uri].unique.transact(transactor).attempt
      } yield {
        result.isLeft mustBe true
      }
    }
  }

  "mediaTypeGet" should "fail for invalid media type" in runIO {
    DoobieTransactor.create[IO](dbConfig).use { transactor =>
      for {
        _ <- sql"CREATE TABLE media_error_test (media_type VARCHAR(255))".update.run.transact(transactor)
        _ <- sql"INSERT INTO media_error_test VALUES ('not/a/valid/media/type')".update.run.transact(transactor)
        result <- sql"SELECT media_type FROM media_error_test".query[MediaType].unique.transact(transactor).attempt
      } yield {
        result.isLeft mustBe true
      }
    }
  }
}
