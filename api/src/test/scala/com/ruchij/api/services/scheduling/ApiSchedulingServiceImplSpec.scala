package com.ruchij.api.services.scheduling

import cats.effect.{IO, Resource}
import com.ruchij.api.daos.credentials.DoobieCredentialsDao
import com.ruchij.api.daos.permission.DoobieVideoPermissionDao
import com.ruchij.api.daos.resettoken.DoobieCredentialsResetTokenDao
import com.ruchij.api.daos.title.DoobieVideoTitleDao
import com.ruchij.api.daos.user.DoobieUserDao
import com.ruchij.api.daos.user.models.Email
import com.ruchij.api.services.authentication.AuthenticationService.Password
import com.ruchij.api.services.config.models.ApiConfigKey
import com.ruchij.api.services.config.models.ApiConfigKey.ApiConfigKeySpace
import com.ruchij.api.services.hashing.BCryptPasswordHashingService
import com.ruchij.api.services.user.UserServiceImpl
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.snapshot.DoobieSnapshotDao
import com.ruchij.core.daos.video.DoobieVideoDao
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata}
import com.ruchij.core.external.embedded.EmbeddedExternalServiceProvider
import com.ruchij.core.kv.{InMemoryKeyValueStore, KeySpacedKeyValueStore}
import com.ruchij.core.messaging.inmemory.Fs2PubSub
import com.ruchij.core.messaging.models.CommittableRecord
import com.ruchij.core.services.cli.CliCommandRunner
import com.ruchij.core.services.config.ConfigurationServiceImpl
import com.ruchij.core.services.download.Http4sDownloadService
import com.ruchij.core.services.hashing.MurmurHash3Service
import com.ruchij.core.services.renderer.SpaSiteRenderer
import com.ruchij.core.services.repository.InMemoryRepositoryService
import com.ruchij.core.services.scheduling.models.WorkerStatusUpdate
import com.ruchij.core.services.video.{VideoAnalysisServiceImpl, VideoServiceImpl, YouTubeVideoDownloader}
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import com.ruchij.core.test.Providers
import com.ruchij.core.types.JodaClock
import doobie.ConnectionIO
import fs2.Stream
import org.http4s._
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.headers.{`Content-Length`, `Content-Type`}
import org.http4s.implicits._
import org.joda.time.DateTime
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

class ApiSchedulingServiceImplSpec extends AnyFlatSpec with Matchers with MockFactory with OptionValues {
  import ExecutionContext.Implicits.global

  "SchedulingServiceImpl" should "save the scheduled video task" in runIO {
    val videoUrl: Uri = uri"https://www.pornone.com/hello-world"

    val dateTime = DateTime.now()
    implicit val jodaClock: JodaClock[IO] = Providers.stubClock[IO](dateTime)

    val client =
      Client[IO] { request =>
        Resource.eval {
          HttpRoutes
            .of[IO] {
              case GET -> Root / "hello-world" =>
                Ok {
                  """
                    <div>
                      <video id="pornone-video-player">
                        <source src="https://pornone.com/video/hello_world_1920x1080_4000k.mp4"/>
                      </video>
                      <script data-react-helmet="true">
                        {
                          "name": "This is hello world",
                          "thumbnailUrl": "https://pornone.com/thumbnail/hello-world.jpg",
                          "duration": "P0DT0H35M34S"
                        }
                      </script>
                    </div>
                  """
                }

              case HEAD -> Root / "video" / "hello_world_1920x1080_4000k.mp4" =>
                IO.pure {
                  Response[IO](status = Status.Ok, headers = Headers(`Content-Length`.unsafeFromLong(1988)))
                }

              case GET -> Root / "thumbnail" / "hello-world.jpg" =>
                IO.pure {
                  Response[IO](
                    status = Status.Ok,
                    headers = Headers(`Content-Length`.unsafeFromLong(100), `Content-Type`(MediaType.image.jpeg)),
                    body = Stream[IO, Byte](1).repeat.take(100)
                  )
                }
            }
            .orNotFound
            .run(request)
        }
      }

    val storageConfiguration = new StorageConfiguration { override val imageFolder: String = "/images" }
    val hashingService = new MurmurHash3Service[IO]
    val repositoryService = new InMemoryRepositoryService[IO](new ConcurrentHashMap())
    val downloadService = new Http4sDownloadService[IO](client, repositoryService)
    val configurationService =
      new ConfigurationServiceImpl[IO, ApiConfigKey](
        new KeySpacedKeyValueStore[IO, ApiConfigKey[_], String](ApiConfigKeySpace, new InMemoryKeyValueStore[IO])
      )

    new EmbeddedExternalServiceProvider[IO].transactor
      .use { implicit transaction =>
        for {
          videoAnalysisService <- IO.pure {
            new VideoAnalysisServiceImpl[IO, ConnectionIO](
              hashingService,
              downloadService,
              mock[YouTubeVideoDownloader[IO]],
              client,
              mock[SpaSiteRenderer[IO]],
              mock[CliCommandRunner[IO]],
              DoobieVideoMetadataDao,
              DoobieFileResourceDao,
              storageConfiguration
            )
          }

          videoService =
            new VideoServiceImpl[IO, ConnectionIO](repositoryService, DoobieVideoDao, DoobieSnapshotDao, DoobieFileResourceDao)

          videoId = "pornone-8685422022d86a13"

          expectedScheduledDownloadVideo = ScheduledVideoDownload(
            dateTime,
            dateTime,
            SchedulingStatus.Queued,
            0,
            VideoMetadata(
              videoUrl,
              videoId,
              CustomVideoSite.PornOne,
              "This is hello world",
              (35 minutes) + (34 seconds),
              1988,
              FileResource(
                s"$videoId-9e2c97c4",
                dateTime,
                s"${storageConfiguration.imageFolder}/thumbnail-$videoId-hello-world.jpg",
                MediaType.image.jpeg,
                100
              )
            ),
            None
          )

          passwordHashingService = new BCryptPasswordHashingService[IO]
          userService = new UserServiceImpl[IO, ConnectionIO](
            passwordHashingService,
            DoobieUserDao,
            DoobieCredentialsDao,
            DoobieCredentialsResetTokenDao,
            DoobieVideoTitleDao,
            DoobieVideoPermissionDao
          )

          user <- userService.create("Ruchira", "Jayasekara", Email("admin@ruchij.com"), Password("Password"))

          scheduledVideoDownloadUpdatesPubSub <- Fs2PubSub[IO, ScheduledVideoDownload]
          workerStatusUpdatesPubSub <- Fs2PubSub[IO, WorkerStatusUpdate]

          receivedMessagesFiber <- scheduledVideoDownloadUpdatesPubSub
            .subscribe("SchedulingServiceImplSpec")
            .take(1)
            .compile
            .toList
            .map {
              _.map { case CommittableRecord(message, _) => message }
            }
            .start

          apiSchedulingService = new ApiSchedulingServiceImpl[IO, ConnectionIO](
            videoService,
            videoAnalysisService,
            scheduledVideoDownloadUpdatesPubSub,
            workerStatusUpdatesPubSub,
            configurationService,
            DoobieSchedulingDao,
            DoobieVideoTitleDao,
            DoobieVideoPermissionDao
          )

          scheduledVideoDownload <- apiSchedulingService.schedule(videoUrl, user.id)
          _ <- IO.delay { scheduledVideoDownload mustBe expectedScheduledDownloadVideo }

          receivedMessages <- receivedMessagesFiber.join.flatMap(_.embedNever).withTimeout(5 seconds)

          _ <- IO.delay { receivedMessages.headOption mustBe Some(expectedScheduledDownloadVideo) }
        } yield (): Unit
      }
  }
}
