package com.ruchij.core.services.video

import cats.data.OptionT
import cats.effect.std.Dispatcher
import cats.effect.{Async, IO, Ref, Resource, Sync}
import cats.implicits._
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata}
import com.ruchij.core.daos.videometadata.models.VideoSite.YTDownloaderSite
import com.ruchij.core.external.containers.SpaRendererContainer
import com.ruchij.core.services.cli.{CliCommandRunner, CliCommandRunnerImpl}
import com.ruchij.core.services.download.DownloadService
import com.ruchij.core.services.download.models.DownloadResult
import com.ruchij.core.services.hashing.HashingService
import com.ruchij.core.services.renderer.{SpaSiteRenderer, SpaSiteRendererImpl}
import com.ruchij.core.services.video.VideoAnalysisService.NewlyCreated
import com.ruchij.core.services.video.models.VideoAnalysisResult
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.Providers
import com.ruchij.core.test.data.CoreTestData
import com.ruchij.core.test.matchers.matchCaseInsensitivelyTo
import com.ruchij.core.types.Clock
import com.ruchij.core.types.FunctionKTypes.identityFunctionK
import fs2.Stream
import org.http4s.client.Client
import org.http4s.implicits.http4sLiteralsSyntax
import org.http4s.jdkhttpclient.JdkHttpClient
import org.http4s.{HttpApp, MediaType, Query, Uri}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.net.http.HttpClient
import java.net.http.HttpClient.Redirect
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class VideoAnalysisServiceImplSpec extends AnyFlatSpec with MockFactory with Matchers {

  "analyze(uri: Uri) in VideoAnalysisService" should "analyse a PornOne video URL" in runIO {
    analyze[IO](uri"https://pornone.com/bbc/sk-rl-tte-nik-l-onlyfans/277968339/").semiflatMap { videoAnalysisResult =>
      IO.delay {
        videoAnalysisResult.title must matchCaseInsensitivelyTo("Sk\u00e0rl\u00e9tte Nik0l\u00e9 Onlyfans #1")
        videoAnalysisResult.duration mustBe ((34 minutes) + (19 seconds))
        videoAnalysisResult.size mustBe 1056449934
        videoAnalysisResult.thumbnail mustBe uri"https://th-eu4.pornone.com/t/39/277968339/b11.jpg"
        videoAnalysisResult.videoSite mustBe CustomVideoSite.PornOne
      }
    }.value
  }

  it should "analyse a SxyPrn video URL" in runIO {
    analyze[IO](uri"https://sxyprn.com/post/661d0cec4b19c.html").semiflatMap { videoAnalysisResult =>
      IO.delay {
        videoAnalysisResult.title must include("Spencer Scott")
        videoAnalysisResult.duration mustBe ((31 minutes) + (26 seconds))
        videoAnalysisResult.size mustBe 494961055
        videoAnalysisResult.videoSite mustBe CustomVideoSite.SxyPrn
      }
    }.value
  }

  it should "analyse a FreshPorno video URL" ignore runIO {
    analyze[IO](uri"https://freshporno.net/videos/sharpening-her-skills/").semiflatMap { videoAnalysisResult =>
      IO.delay {
        videoAnalysisResult.title mustBe "Sharpening Her Skills"
        videoAnalysisResult.duration mustBe ((45 minutes) + (30 seconds))
        videoAnalysisResult.size mustBe 1699743230
        videoAnalysisResult.videoSite mustBe CustomVideoSite.FreshPorno
      }
    }.value
  }

  it should "analyse a SpankBang video URL" ignore runIO {
    analyze[IO](uri"https://spankbang.com/52kje/video/the+crooked+cops").semiflatMap { videoAnalysisResult =>
      IO.delay {
        videoAnalysisResult.title mustBe "The Crooked Cops"
        videoAnalysisResult.duration mustBe ((38 minutes) + (44 seconds))
        videoAnalysisResult.size mustBe 676023587
        videoAnalysisResult.thumbnail mustBe uri"https://tbi.sb-cd.com/t/8518010/8/5/w:300/t6-enh/the-crooked-cops.jpg"
        videoAnalysisResult.videoSite mustBe CustomVideoSite.SpankBang
      }
    }.value
  }

  it should "analyse a XFreeHD video URL" in runIO {
    analyze[IO](uri"https://www.xfreehd.com/video/343591/breaking-white-blonde-booty-giselle-palmer").semiflatMap {
      videoAnalysisResult =>
        IO.delay {
          videoAnalysisResult.title mustBe "BREAKING WHITE BLONDE BOOTY - GISELLE PALMER"
          videoAnalysisResult.duration mustBe ((37 minutes) + (1 seconds))
          videoAnalysisResult.size mustBe 584122827
          Set(
            uri"https://xfreehd.com/media/videos/tmb10/343591/1b.jpg",
            uri"https://image.xfreehd.com/media/videos/tmb10/343591/1b.jpg"
          ) must contain(videoAnalysisResult.thumbnail)
          videoAnalysisResult.videoSite mustBe CustomVideoSite.XFreeHD
        }
    }.value
  }

  it should "analyse a TXXX video URL" ignore runIO {
    analyze[IO](uri"https://txxx.com/videos/17258955/first-time-bbc-with-balls-deep-anal/?fr=18404847&rp=1").semiflatMap {
      videoAnalysisResult =>
        IO.delay {
          videoAnalysisResult.title mustBe "First Time Bbc With Balls Deep Anal"
          videoAnalysisResult.duration mustBe ((52 minutes) + (5 seconds))
          videoAnalysisResult.size mustBe 431177059
          videoAnalysisResult.thumbnail mustBe uri"https://tn.txxx.tube/contents/videos_screenshots/17258000/17258955/preview.jpg"
          videoAnalysisResult.videoSite mustBe CustomVideoSite.TXXX
        }
    }.value
  }

  it should "analyse a UPornia video URL" ignore runIO {
    analyze[IO](uri"https://upornia.com/videos/4810631/gets-two-black-cocks-in-every-hole-with-bailey-nicole/").semiflatMap {
      videoAnalysisResult =>
        IO.delay {
          videoAnalysisResult.title mustBe "Gets Two Black Cocks In Every Hole With Bailey Nicole"
          videoAnalysisResult.duration mustBe ((16 minutes) + (15 seconds))
          videoAnalysisResult.size mustBe 188184287
          videoAnalysisResult.thumbnail mustBe uri"https://tn.upornia.com/contents/videos_screenshots/4810000/4810631/preview.jpg"
          videoAnalysisResult.videoSite mustBe CustomVideoSite.UPornia
        }
    }.value
  }

  it should "analyse a EPorner video URL" ignore runIO {
    analyze[IO](uri"https://www.eporner.com/video-wxed3sMHlEC/bbc-breeds-wife/").semiflatMap { videoAnalysisResult =>
      IO.delay {
        videoAnalysisResult.title mustBe "BBC Breeds Wife"
        videoAnalysisResult.duration mustBe ((29 minutes) + (12 seconds))
        videoAnalysisResult.thumbnail.path mustBe uri"https://static-au-cdn.eporner.com/thumbs/static4/1/12/120/12018340/5_360.jpg".path
        videoAnalysisResult.videoSite mustBe YTDownloaderSite("eporner")
      }
    }.value
  }

  it should "analyse a YouTube video URL" in runIO {
    analyze[IO](uri"https://www.youtube.com/watch?v=2Vv-BfVoq4g&list=RDMM-fR-duU1Qjk&start_radio=1").semiflatMap {
      videoAnalysisResult =>
        IO.delay {
          videoAnalysisResult.title mustBe "Ed Sheeran - Perfect (Official Music Video)"
          videoAnalysisResult.duration mustBe ((4 minutes) + (42 seconds))
          Set(
            uri"https://i.ytimg.com/vi/2Vv-BfVoq4g/hqdefault.jpg",
            uri"https://i.ytimg.com/vi/2Vv-BfVoq4g/sddefault.jpg",
            uri"https://i.ytimg.com/vi_webp/2Vv-BfVoq4g/sddefault.webp"
          ) must contain(videoAnalysisResult.thumbnail.copy(query = Query.empty))
          videoAnalysisResult.videoSite mustBe YTDownloaderSite("youtube")
        }
    }.value
  }

  "metadata(uri: Uri) when no existing video metadata is found" should
    "persist the thumbnail and the video metadata in a single transaction and return NewlyCreated" in runIO {
    val hashingService = mock[HashingService[IO]]
    val downloadService = mock[DownloadService[IO]]
    val youTubeVideoDownloader = mock[YouTubeVideoDownloader[IO]]
    val videoMetadataDao = mock[VideoMetadataDao[IO]]
    val fileResourceDao = mock[FileResourceDao[IO]]

    val videoUri = uri"https://www.youtube.com/watch?v=abc123"

    val analysisResult =
      VideoAnalysisResult(
        url = videoUri,
        videoSite = YTDownloaderSite("youtube"),
        title = "Sample title",
        duration = 5 minutes,
        size = 1000000L,
        thumbnail = uri"https://example.com/thumb.jpg"
      )

    (videoMetadataDao.findByUrl _).expects(videoUri).returns(IO.pure(None))
    (youTubeVideoDownloader.videoInformation _).expects(videoUri).returns(IO.pure(analysisResult))
    (hashingService.hash _).expects(*).returns(IO.pure("hash")).anyNumberOfTimes()

    val downloadResult =
      DownloadResult.create[IO](
        analysisResult.thumbnail,
        downloadedFileKey = "downloaded-key",
        size = 100L,
        mediaType = MediaType.image.jpeg
      )(Stream.empty)

    (downloadService.download _)
      .expects(*, *)
      .returns(Resource.pure[IO, DownloadResult[IO]](downloadResult))

    (fileResourceDao.insert _).expects(*).returns(IO.pure(1)).once()
    (videoMetadataDao.insert _).expects(*).returns(IO.pure(1)).once()

    val service =
      buildService(hashingService, downloadService, youTubeVideoDownloader, videoMetadataDao, fileResourceDao)

    service.metadata(videoUri).flatMap {
      case NewlyCreated(metadata) =>
        IO.delay {
          metadata.url mustBe videoUri
          metadata.title mustBe "Sample title"
          metadata.size mustBe 1000000L
          metadata.duration mustBe (5 minutes)
          metadata.videoSite mustBe YTDownloaderSite("youtube")
        }

      case other =>
        IO.raiseError(new AssertionError(s"Expected NewlyCreated, got $other"))
    }
  }

  it should "re-raise the original failure when the persistence transaction fails " +
    "and the recovery lookup confirms the row was not persisted" in runIO {
    val hashingService = mock[HashingService[IO]]
    val downloadService = mock[DownloadService[IO]]
    val youTubeVideoDownloader = mock[YouTubeVideoDownloader[IO]]
    val videoMetadataDao = mock[VideoMetadataDao[IO]]
    val fileResourceDao = mock[FileResourceDao[IO]]

    val videoUri = uri"https://www.youtube.com/watch?v=fail"

    val analysisResult =
      VideoAnalysisResult(
        url = videoUri,
        videoSite = YTDownloaderSite("youtube"),
        title = "Failing title",
        duration = 1 minute,
        size = 500L,
        thumbnail = uri"https://example.com/thumb-fail.jpg"
      )

    // Called twice: up-front existence check, then recovery lookup after the
    // persistence transaction fails. Both return None, so the original error
    // is re-raised.
    (videoMetadataDao.findByUrl _).expects(videoUri).returns(IO.pure(None)).twice()
    (youTubeVideoDownloader.videoInformation _).expects(videoUri).returns(IO.pure(analysisResult))
    (hashingService.hash _).expects(*).returns(IO.pure("hash")).anyNumberOfTimes()

    val downloadResult =
      DownloadResult.create[IO](
        analysisResult.thumbnail,
        downloadedFileKey = "downloaded-key",
        size = 100L,
        mediaType = MediaType.image.jpeg
      )(Stream.empty)

    (downloadService.download _)
      .expects(*, *)
      .returns(Resource.pure[IO, DownloadResult[IO]](downloadResult))

    val dbError = new RuntimeException("simulated DB failure")

    Ref.of[IO, Int](0).flatMap { fileInsertEvaluations =>
      // The mock returns a single IO that, every time it is evaluated, increments the counter
      // and then raises `dbError`. This lets us count *runtime evaluations* (which retries
      // would increase) rather than *mock invocations* (which happen once at IO construction).
      (fileResourceDao.insert _)
        .expects(*)
        .returns(fileInsertEvaluations.update(_ + 1) *> IO.raiseError[Int](dbError))
        .once()

      // `videoMetadataDao.insert(videoMetadata)` is invoked at IO construction so that
      // `productR` has an IO node to compose, even though its body is never evaluated
      // (the failing left-hand side short-circuits `*>`).
      (videoMetadataDao.insert _).expects(*).returns(IO.pure(1)).once()

      val service =
        buildService(hashingService, downloadService, youTubeVideoDownloader, videoMetadataDao, fileResourceDao)

      for {
        result <- service.metadata(videoUri).attempt
        evaluations <- fileInsertEvaluations.get
        _ <- IO.delay {
          result.left.toOption mustBe Some(dbError)
          evaluations mustBe 1
        }
      } yield ()
    }
  }

  it should "swallow the persistence failure and return NewlyCreated when the recovery lookup " +
    "finds the row (concurrent insert race)" in runIO {
    val hashingService = mock[HashingService[IO]]
    val downloadService = mock[DownloadService[IO]]
    val youTubeVideoDownloader = mock[YouTubeVideoDownloader[IO]]
    val videoMetadataDao = mock[VideoMetadataDao[IO]]
    val fileResourceDao = mock[FileResourceDao[IO]]

    val videoUri = uri"https://www.youtube.com/watch?v=race"

    val analysisResult =
      VideoAnalysisResult(
        url = videoUri,
        videoSite = YTDownloaderSite("youtube"),
        title = "Race title",
        duration = 2 minutes,
        size = 1234L,
        thumbnail = uri"https://example.com/thumb-race.jpg"
      )

    val concurrentlyPersisted = VideoMetadata(
      url = videoUri,
      id = "youtube-race",
      videoSite = YTDownloaderSite("youtube"),
      title = "Race title (persisted by concurrent writer)",
      duration = 2 minutes,
      size = 1234L,
      thumbnail = FileResource(
        id = "thumbnail-race",
        createdAt = CoreTestData.Timestamp,
        path = "image-folder/thumb-race.jpg",
        mediaType = MediaType.image.jpeg,
        size = 100L
      )
    )

    // Models the race: the up-front existence check sees no row, but between
    // then and the persistence transaction, a concurrent writer commits. The
    // transaction fails (e.g. unique-constraint violation) and the recovery
    // lookup now finds the row, which the new logic interprets as "the
    // desired persisted state has been reached" and swallows the error.
    inSequence {
      (videoMetadataDao.findByUrl _).expects(videoUri).returns(IO.pure(None)).once()
      (videoMetadataDao.findByUrl _).expects(videoUri).returns(IO.pure(Some(concurrentlyPersisted))).once()
    }

    (youTubeVideoDownloader.videoInformation _).expects(videoUri).returns(IO.pure(analysisResult))
    (hashingService.hash _).expects(*).returns(IO.pure("hash")).anyNumberOfTimes()

    val downloadResult =
      DownloadResult.create[IO](
        analysisResult.thumbnail,
        downloadedFileKey = "downloaded-key",
        size = 100L,
        mediaType = MediaType.image.jpeg
      )(Stream.empty)

    (downloadService.download _)
      .expects(*, *)
      .returns(Resource.pure[IO, DownloadResult[IO]](downloadResult))

    val dbError = new RuntimeException("simulated unique-constraint violation")

    (fileResourceDao.insert _).expects(*).returns(IO.raiseError[Int](dbError)).once()
    (videoMetadataDao.insert _).expects(*).returns(IO.pure(1)).once()

    val service =
      buildService(hashingService, downloadService, youTubeVideoDownloader, videoMetadataDao, fileResourceDao)

    service.metadata(videoUri).flatMap {
      case NewlyCreated(metadata) =>
        IO.delay {
          // The locally-built VideoMetadata is what gets returned, *not* the row
          // returned by findByUrl — the recovery lookup is only used to confirm
          // that persistence succeeded somewhere.
          metadata.url mustBe videoUri
          metadata.title mustBe "Race title"
          metadata.duration mustBe (2 minutes)
          metadata.size mustBe 1234L
          metadata.videoSite mustBe YTDownloaderSite("youtube")
        }

      case other =>
        IO.raiseError(new AssertionError(s"Expected NewlyCreated, got $other"))
    }
  }

  it should "propagate the recovery lookup error (and lose the original DB error) " +
    "when the recovery findByUrl itself fails" in runIO {
    val hashingService = mock[HashingService[IO]]
    val downloadService = mock[DownloadService[IO]]
    val youTubeVideoDownloader = mock[YouTubeVideoDownloader[IO]]
    val videoMetadataDao = mock[VideoMetadataDao[IO]]
    val fileResourceDao = mock[FileResourceDao[IO]]

    val videoUri = uri"https://www.youtube.com/watch?v=lookup-fails"

    val analysisResult =
      VideoAnalysisResult(
        url = videoUri,
        videoSite = YTDownloaderSite("youtube"),
        title = "Lookup fails title",
        duration = 1 minute,
        size = 500L,
        thumbnail = uri"https://example.com/thumb.jpg"
      )

    val dbError = new RuntimeException("simulated DB failure during insert")
    val lookupError = new RuntimeException("simulated DB failure during recovery lookup")

    // Up-front existence check returns None (so we proceed to createMetadata),
    // and the recovery lookup itself fails. The recovery error is what surfaces
    // — the original `dbError` is lost because the recovery branch only re-raises
    // the original on a `None` result, not on a lookup failure.
    inSequence {
      (videoMetadataDao.findByUrl _).expects(videoUri).returns(IO.pure(None)).once()
      (videoMetadataDao.findByUrl _).expects(videoUri).returns(IO.raiseError[Option[VideoMetadata]](lookupError)).once()
    }

    (youTubeVideoDownloader.videoInformation _).expects(videoUri).returns(IO.pure(analysisResult))
    (hashingService.hash _).expects(*).returns(IO.pure("hash")).anyNumberOfTimes()

    val downloadResult =
      DownloadResult.create[IO](
        analysisResult.thumbnail,
        downloadedFileKey = "downloaded-key",
        size = 100L,
        mediaType = MediaType.image.jpeg
      )(Stream.empty)

    (downloadService.download _)
      .expects(*, *)
      .returns(Resource.pure[IO, DownloadResult[IO]](downloadResult))

    (fileResourceDao.insert _).expects(*).returns(IO.raiseError[Int](dbError)).once()
    (videoMetadataDao.insert _).expects(*).returns(IO.pure(1)).once()

    val service =
      buildService(hashingService, downloadService, youTubeVideoDownloader, videoMetadataDao, fileResourceDao)

    service.metadata(videoUri).attempt.flatMap { result =>
      IO.delay {
        result.left.toOption mustBe Some(lookupError)
      }
    }
  }

  private def buildService(
    hashingService: HashingService[IO],
    downloadService: DownloadService[IO],
    youTubeVideoDownloader: YouTubeVideoDownloader[IO],
    videoMetadataDao: VideoMetadataDao[IO],
    fileResourceDao: FileResourceDao[IO]
  ): VideoAnalysisServiceImpl[IO, IO] = {
    val client: Client[IO] = Client.fromHttpApp[IO](HttpApp.notFound[IO])
    val spaSiteRenderer = mock[SpaSiteRenderer[IO]]
    val cliCommandRunner = mock[CliCommandRunner[IO]]
    val storageConfiguration = StorageConfiguration("video-folder", "image-folder", List.empty)

    implicit val clock: Clock[IO] = Providers.stubClock[IO](CoreTestData.Timestamp)

    new VideoAnalysisServiceImpl[IO, IO](
      hashingService,
      downloadService,
      youTubeVideoDownloader,
      client,
      spaSiteRenderer,
      cliCommandRunner,
      videoMetadataDao,
      fileResourceDao,
      storageConfiguration
    )
  }

  private def isCI[F[_]: Sync]: F[Boolean] =
    Sync[F].delay(sys.env.get("CI")).map(_.flatMap(_.toBooleanOption).getOrElse(false))

  private def analyze[F[_]: Async: Clock](videoUri: Uri): OptionT[F, VideoAnalysisResult] =
    OptionT
      .liftF(isCI[F])
      .filter(isCi => !isCi)
      .productR {
        OptionT.liftF {
          Sync[F]
            .delay(HttpClient.newBuilder().followRedirects(Redirect.NORMAL).build())
            .flatMap { javaHttpClient =>
              val resources =
                for {
                  spaSiteRendererConfiguration <- SpaRendererContainer.create[F]
                  httpClient = JdkHttpClient[F](javaHttpClient)
                  dispatcher <- Dispatcher.parallel[F]
                } yield (spaSiteRendererConfiguration, httpClient, dispatcher)

              resources
                .use {
                  case (spaSiteRendererConfiguration, httpClient, dispatcher) =>
                    val hashingService = mock[HashingService[F]]
                    val downloadService = mock[DownloadService[F]]
                    val videoMetadataDao = mock[VideoMetadataDao[F]]
                    val fileResourceDao = mock[FileResourceDao[F]]
                    val storageConfiguration = StorageConfiguration("video-folder", "image-folder", List.empty)
                    val cliCommandRunner: CliCommandRunner[F] = new CliCommandRunnerImpl[F](dispatcher)
                    val youTubeVideoDownloader = new YouTubeVideoDownloaderImpl[F](cliCommandRunner, httpClient, None)
                    val spaSiteRenderer =
                      new SpaSiteRendererImpl[F](httpClient, spaSiteRendererConfiguration)

                    val videoAnalysisServiceImpl =
                      new VideoAnalysisServiceImpl[F, F](
                        hashingService,
                        downloadService,
                        youTubeVideoDownloader,
                        httpClient,
                        spaSiteRenderer,
                        cliCommandRunner,
                        videoMetadataDao,
                        fileResourceDao,
                        storageConfiguration
                      )

                    videoAnalysisServiceImpl.analyze(videoUri)
                }
            }
        }
      }
}
