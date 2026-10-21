package com.ruchij.api.services.playlist

import cats.effect.{IO, Ref}
import cats.implicits._
import cats.~>
import com.ruchij.api.daos.playlist.DoobiePlaylistDao
import com.ruchij.api.daos.playlist.models.{Playlist, PlaylistSortBy}
import com.ruchij.api.test.data.ApiTestData
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.permission.DoobieVideoPermissionDao
import com.ruchij.core.daos.permission.models.VideoPermission
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.video.DoobieVideoDao
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.exceptions.{ResourceNotFoundException, ValidationException}
import com.ruchij.core.external.CoreResourcesProvider
import com.ruchij.core.external.containers.ContainerCoreResourcesProvider
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.services.models.Order
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import com.ruchij.core.test.data.CoreTestData
import com.ruchij.core.types.Clock
import doobie.free.connection.ConnectionIO
import fs2.Stream
import org.http4s.MediaType
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/** Runs PlaylistServiceImpl against real DAOs, on both H2 (PostgreSQL mode) and PostgreSQL. */
class PlaylistServiceIntegrationSpec extends AnyFlatSpec with Matchers with OptionValues {

  private val providers: List[(String, () => CoreResourcesProvider[IO])] =
    List(
      "H2" -> (() => new EmbeddedCoreResourcesProvider[IO]),
      "PostgreSQL" -> (() => new ContainerCoreResourcesProvider[IO])
    )

  private val storageConfiguration = StorageConfiguration("videos", "images", List.empty)

  private val NormalUserPlaylistId = "normal-user-playlist"

  private val AsNormalUser = Some(ApiTestData.NormalUser.id)

  private class RecordingRepositoryService(written: Ref[IO, Map[String, Long]]) extends RepositoryService[IO] {
    override type BackedType = String

    override def write(key: Key, data: Stream[IO, Byte]): Stream[IO, Nothing] =
      Stream.eval(written.update(_.updated(key, 0L))) >>
        data.chunks
          .evalMap(chunk => written.update(files => files.updated(key, files.getOrElse(key, 0L) + chunk.size)))
          .drain

    override def read(key: Key, start: Option[Long], end: Option[Long]): IO[Option[Stream[IO, Byte]]] = IO.pure(None)

    override def size(key: Key): IO[Option[Long]] = written.get.map(_.get(key))

    override def list(key: Key): Stream[IO, Key] = Stream.empty

    override def exists(key: Key): IO[Boolean] = written.get.map(_.contains(key))

    override def backedType(key: Key): IO[BackedType] = IO.pure("memory")

    override def delete(key: Key): IO[Boolean] =
      written.modify(files => (files - key, files.contains(key)))

    override def fileType(key: Key): IO[Option[MediaType]] = IO.pure(None)
  }

  private def scheduledVideo(video: Video): ScheduledVideoDownload =
    ScheduledVideoDownload(
      CoreTestData.Timestamp,
      CoreTestData.Timestamp,
      SchedulingStatus.Completed,
      video.videoMetadata.size,
      video.videoMetadata,
      Some(CoreTestData.Timestamp),
      None
    )

  private val youTubeId = CoreTestData.YouTubeVideo.videoMetadata.id
  private val localId = CoreTestData.LocalVideo.videoMetadata.id
  private val spankBangId = CoreTestData.SpankBangVideo.videoMetadata.id
  private val videoIds = List(youTubeId, localId, spankBangId)

  // The normal user may view the YouTube and local videos, but NOT the SpankBang video
  private val setUpData: ConnectionIO[Unit] =
    ApiTestData.setUpData *>
      List(CoreTestData.YouTubeVideo, CoreTestData.LocalVideo, CoreTestData.SpankBangVideo)
        .traverse(video => DoobieSchedulingDao.insert(scheduledVideo(video))) *>
      List(youTubeId, localId)
        .traverse { videoId =>
          DoobieVideoPermissionDao.insert(VideoPermission(CoreTestData.Timestamp, videoId, ApiTestData.NormalUser.id))
        }
        .void

  private case class Fixture(
    service: PlaylistServiceImpl[IO, ConnectionIO],
    playlistDao: DoobiePlaylistDao,
    written: Ref[IO, Map[String, Long]],
    transaction: ConnectionIO ~> IO
  ) {
    def setVideos(videoIds: Seq[String], maybeUserId: Option[String]): IO[Playlist] =
      service.updatePlaylist(NormalUserPlaylistId, None, None, Some(videoIds), maybeUserId)
  }

  private def runTest(provider: () => CoreResourcesProvider[IO], maxAlbumArtSize: Long = 1024)(
    test: Fixture => IO[Any]
  ): Unit =
    runIO {
      provider().transactor.use { implicit transaction =>
        for {
          _ <- transaction(setUpData)
          written <- Ref.of[IO, Map[String, Long]](Map.empty)
          playlistDao = new DoobiePlaylistDao(DoobieFileResourceDao, DoobieVideoDao)
          service = new PlaylistServiceImpl[IO, ConnectionIO](
            playlistDao,
            DoobieFileResourceDao,
            new RecordingRepositoryService(written),
            storageConfiguration,
            maxAlbumArtSize
          )
          timestamp <- Clock[IO].timestamp
          _ <- transaction {
            playlistDao.insert(
              Playlist(NormalUserPlaylistId, ApiTestData.NormalUser.id, timestamp, "Normal", None, Seq.empty, None)
            )
          }
          _ <- test(Fixture(service, playlistDao, written, transaction))
        } yield ()
      }
    }

  private def videoIdsOf(playlist: Playlist): Seq[String] = playlist.videos.map(_.videoMetadata.id)

  providers.foreach {
    case (databaseName, provider) =>
      s"updatePlaylist ($databaseName)" should "not modify another user's playlist, and roll back" in
        runTest(provider) { fixture =>
          for {
            // The admin owns a playlist containing every video
            _ <- fixture.service.create("Admin", None, ApiTestData.AdminUser.id).flatMap { adminPlaylist =>
              fixture.service
                .updatePlaylist(adminPlaylist.id, None, None, Some(videoIds), None)
                .flatMap { _ =>
                  fixture.service
                    .updatePlaylist(adminPlaylist.id, Some("Hijacked"), None, Some(List(youTubeId)), AsNormalUser)
                    .error
                    .map(_ mustBe a[ResourceNotFoundException])
                }
                .productR(fixture.service.fetchById(adminPlaylist.id, None))
                .map { unchanged =>
                  unchanged.title mustBe "Admin"
                  videoIdsOf(unchanged) mustBe videoIds
                }
            }
          } yield ()
      }

      it should "reject videos the non-admin user does not have permission for, changing nothing" in
        runTest(provider) { fixture =>
          for {
            _ <- fixture.setVideos(List(youTubeId), AsNormalUser)
            error <- fixture.service
              .updatePlaylist(
                NormalUserPlaylistId,
                Some("Changed"),
                None,
                Some(List(localId, spankBangId)),
                AsNormalUser
              )
              .error
            _ = error mustBe a[ResourceNotFoundException]
            _ = error.getMessage must include(spankBangId)
            playlist <- fixture.service.fetchById(NormalUserPlaylistId, AsNormalUser)
          } yield {
            playlist.title mustBe "Normal"
            videoIdsOf(playlist) mustBe List(youTubeId)
          }
      }

      it should "allow an admin to add any video to any playlist" in runTest(provider) { fixture =>
        fixture
          .setVideos(List(spankBangId, youTubeId), None)
          .map(playlist => videoIdsOf(playlist) mustBe List(spankBangId, youTubeId))
      }

      it should "persist the submitted video order and drop duplicates, keeping the first occurrence" in
        runTest(provider) { fixture =>
          for {
            updated <- fixture.setVideos(List(localId, youTubeId, localId), AsNormalUser)
            fetched <- fixture.service.fetchById(NormalUserPlaylistId, AsNormalUser)
            reordered <- fixture.setVideos(List(youTubeId, localId), AsNormalUser)
          } yield {
            videoIdsOf(updated) mustBe List(localId, youTubeId)
            videoIdsOf(fetched) mustBe List(localId, youTubeId)
            videoIdsOf(reordered) mustBe List(youTubeId, localId)
          }
      }

      s"fetchById ($databaseName)" should "only return videos the non-admin user has permission for" in
        runTest(provider) { fixture =>
          for {
            // An admin adds a video the owner has no permission for
            _ <- fixture.setVideos(List(spankBangId, youTubeId), None)
            asOwner <- fixture.service.fetchById(NormalUserPlaylistId, AsNormalUser)
            asAdmin <- fixture.service.fetchById(NormalUserPlaylistId, None)
            searchAsOwner <- fixture.service.search(
              None,
              10,
              0,
              Order.Descending,
              PlaylistSortBy.CreatedAt,
              AsNormalUser
            )
          } yield {
            videoIdsOf(asOwner) mustBe List(youTubeId)
            videoIdsOf(asAdmin) mustBe List(spankBangId, youTubeId)
            searchAsOwner.map(videoIdsOf) mustBe List(List(youTubeId))
          }
      }

      s"addAlbumArt ($databaseName)" should "check ownership before writing the file" in runTest(provider) { fixture =>
        for {
          error <- fixture.service
            .addAlbumArt(
              NormalUserPlaylistId,
              "art.png",
              MediaType.image.png,
              Stream.emits[IO, Byte](Array.fill[Byte](10)(1)),
              Some(ApiTestData.AdminUser.id + "-other")
            )
            .error
          written <- fixture.written.get
        } yield {
          error mustBe a[ResourceNotFoundException]
          written mustBe empty
        }
      }

      it should "reject album art larger than the limit and delete the partially written file" in runTest(provider) {
        fixture =>
          for {
            error <- fixture.service
              .addAlbumArt(
                NormalUserPlaylistId,
                "art.png",
                MediaType.image.png,
                Stream.emits[IO, Byte](Array.fill[Byte](2048)(1)).chunkLimit(100).unchunks,
                AsNormalUser
              )
              .error
            written <- fixture.written.get
            playlist <- fixture.service.fetchById(NormalUserPlaylistId, None)
          } yield {
            error mustBe a[ValidationException]
            written mustBe empty
            playlist.albumArt mustBe None
          }
      }

      it should "reject SVG album art" in runTest(provider) { fixture =>
        for {
          error <- fixture.service
            .addAlbumArt(
              NormalUserPlaylistId,
              "art.svg",
              MediaType.image.`svg+xml`,
              Stream.emits[IO, Byte]("<svg/>".getBytes.toList),
              AsNormalUser
            )
            .error
          written <- fixture.written.get
        } yield {
          error mustBe a[ValidationException]
          written mustBe empty
        }
      }

      it should "store album art within the limit" in runTest(provider) { fixture =>
        fixture.service
          .addAlbumArt(
            NormalUserPlaylistId,
            "art.png",
            MediaType.image.png,
            Stream.emits[IO, Byte](Array.fill[Byte](1024)(1)),
            AsNormalUser
          )
          .flatMap { playlist =>
            fixture.written.get.map { written =>
              playlist.albumArt.map(_.size) mustBe Some(1024)
              written.values.toList mustBe List(1024)
            }
          }
      }

      s"deletePlaylist ($databaseName)" should "not delete another user's playlist" in runTest(provider) { fixture =>
        for {
          error <- fixture.service.deletePlaylist(NormalUserPlaylistId, Some("someone-else")).error
          playlist <- fixture.service.fetchById(NormalUserPlaylistId, None)
        } yield {
          error mustBe a[ResourceNotFoundException]
          playlist.id mustBe NormalUserPlaylistId
        }
      }

      def addAlbumArt(fixture: Fixture, fileName: String): IO[Playlist] =
        fixture.service.addAlbumArt(
          NormalUserPlaylistId,
          fileName,
          MediaType.image.png,
          Stream.emits[IO, Byte](Array.fill[Byte](16)(1)),
          AsNormalUser
        )

      def albumArtOf(playlist: Playlist): IO[FileResource] =
        IO.fromOption(playlist.albumArt)(new IllegalStateException("The playlist has no album art"))

      s"album art clean up ($databaseName)" should "delete the replaced album art" in runTest(provider) { fixture =>
        for {
          first <- addAlbumArt(fixture, "first.png").flatMap(albumArtOf)
          second <- addAlbumArt(fixture, "second.png").flatMap(albumArtOf)
          written <- fixture.written.get
          firstResource <- fixture.transaction(DoobieFileResourceDao.getById(first.id))
          secondResource <- fixture.transaction(DoobieFileResourceDao.getById(second.id))
        } yield {
          written.keySet mustBe Set(second.path)
          firstResource mustBe None
          secondResource.map(_.id) mustBe Some(second.id)
        }
      }

      it should "delete removed album art" in runTest(provider) { fixture =>
        for {
          albumArt <- addAlbumArt(fixture, "art.png").flatMap(albumArtOf)
          playlist <- fixture.service.removeAlbumArt(NormalUserPlaylistId, AsNormalUser)
          written <- fixture.written.get
          resource <- fixture.transaction(DoobieFileResourceDao.getById(albumArt.id))
        } yield {
          playlist.albumArt mustBe None
          written mustBe empty
          resource mustBe None
        }
      }

      it should "delete the album art of a deleted playlist" in runTest(provider) { fixture =>
        for {
          albumArt <- addAlbumArt(fixture, "art.png").flatMap(albumArtOf)
          _ <- fixture.service.deletePlaylist(NormalUserPlaylistId, AsNormalUser)
          written <- fixture.written.get
          resource <- fixture.transaction(DoobieFileResourceDao.getById(albumArt.id))
        } yield {
          written mustBe empty
          resource mustBe None
        }
      }

      it should "delete a partially written file when the upload is cancelled" in runTest(provider) { fixture =>
        for {
          fiber <- fixture.service
            .addAlbumArt(
              NormalUserPlaylistId,
              "art.png",
              MediaType.image.png,
              Stream.emits[IO, Byte](Array.fill[Byte](16)(1)) ++ Stream.never[IO],
              AsNormalUser
            )
            .start
          _ <- fixture.written.get.map(_.nonEmpty).iterateUntil(identity).timeout(10.seconds)
          _ <- fiber.cancel
          written <- fixture.written.get
          playlist <- fixture.service.fetchById(NormalUserPlaylistId, None)
        } yield {
          written mustBe empty
          playlist.albumArt mustBe None
        }
      }
  }
}
