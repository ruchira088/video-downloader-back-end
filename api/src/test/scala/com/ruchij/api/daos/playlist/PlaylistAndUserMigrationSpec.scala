package com.ruchij.api.daos.playlist

import cats.effect.{IO, Resource}
import cats.implicits._
import com.ruchij.api.daos.user.DoobieUserDao
import com.ruchij.api.test.data.ApiTestData
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.external.CoreResourcesProvider
import com.ruchij.core.external.containers.ContainerCoreResourcesProvider
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.data.CoreTestData
import com.ruchij.migration.config.DatabaseConfiguration
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import doobie.util.transactor.Transactor
import org.flywaydb.core.Flyway
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.jdk.CollectionConverters.MapHasAsJava
import scala.util.Try

/** Checks that V041 and V042 handle the data already in a database migrated to V040. */
class PlaylistAndUserMigrationSpec extends AnyFlatSpec with Matchers {

  private val providers: List[(String, () => CoreResourcesProvider[IO])] =
    List(
      "H2" -> (() => new EmbeddedCoreResourcesProvider[IO]),
      "PostgreSQL" -> (() => new ContainerCoreResourcesProvider[IO])
    )

  private def flyway(databaseConfiguration: DatabaseConfiguration, target: String): Flyway =
    Flyway
      .configure()
      .dataSource(databaseConfiguration.url, databaseConfiguration.user, databaseConfiguration.password)
      .placeholders(Map("adminHashedPassword" -> CoreResourcesProvider.HashedAdminPassword).asJava)
      .target(target)
      .load()

  private def atVersion40(
    provider: () => CoreResourcesProvider[IO]
  ): Resource[IO, (DatabaseConfiguration, Transactor[IO])] =
    for {
      databaseConfiguration <- provider().databaseConfiguration
      _ <- Resource.eval(IO.blocking(flyway(databaseConfiguration, "40").migrate()))
      transactor <- DoobieTransactor.create[IO](databaseConfiguration)
    } yield databaseConfiguration -> transactor

  private val insertVideos: ConnectionIO[Unit] =
    List(CoreTestData.YouTubeVideo, CoreTestData.SpankBangVideo).traverse_ { video =>
      DoobieFileResourceDao.insert(video.videoMetadata.thumbnail) *>
        DoobieVideoMetadataDao.insert(video.videoMetadata) *>
        DoobieFileResourceDao.insert(video.fileResource) *>
        // Plain SQL, since the DAO targets the latest schema rather than V040
        sql"""
          INSERT INTO video (video_metadata_id, file_resource_id, created_at)
            VALUES (${video.videoMetadata.id}, ${video.fileResource.id}, ${video.createdAt})
        """.update.run *>
        sql"""
          INSERT INTO video_watch_time (video_id, watch_time_in_ms) VALUES (${video.videoMetadata.id}, ${video.watchTime})
        """.update.run
    }

  providers.foreach {
    case (databaseName, provider) =>
      s"V041 ($databaseName)" should "keep existing playlist videos, dropping duplicates and numbering them" in runIO {
        atVersion40(provider).use {
          case (databaseConfiguration, transactor) =>
            val youTubeId = CoreTestData.YouTubeVideo.videoMetadata.id
            val spankBangId = CoreTestData.SpankBangVideo.videoMetadata.id

            for {
              _ <- {
                DoobieUserDao.insert(ApiTestData.NormalUser) *>
                  insertVideos *>
                  sql"""
                    INSERT INTO playlist (id, user_id, title) VALUES ('playlist', ${ApiTestData.NormalUser.id}, 'Title')
                  """.update.run *>
                  List(youTubeId, spankBangId, youTubeId).traverse_ { videoId =>
                    sql"INSERT INTO playlist_video (playlist_id, video_id) VALUES ('playlist', $videoId)".update.run
                  }
              }.transact(transactor)

              _ <- IO.blocking(flyway(databaseConfiguration, "latest").migrate())

              rows <- sql"""
                  SELECT video_id, position_index FROM playlist_video
                    WHERE playlist_id = 'playlist' ORDER BY position_index
                """.query[(String, Int)].to[List].transact(transactor)
            // Without a recorded order, the surviving videos are numbered by video ID
            } yield rows mustBe List(spankBangId -> 0, youTubeId -> 1)
        }
      }

      s"V042 ($databaseName)" should "fail when api_user already holds duplicate emails" in runIO {
        atVersion40(provider).use {
          case (databaseConfiguration, transactor) =>
            for {
              _ <- {
                DoobieUserDao.insert(ApiTestData.NormalUser) *>
                  DoobieUserDao.insert(ApiTestData.NormalUser.copy(id = "duplicate-email-user"))
              }.transact(transactor)

              result <- IO.blocking(Try(flyway(databaseConfiguration, "latest").migrate()))
            } yield result.isFailure mustBe true
        }
      }
  }
}
