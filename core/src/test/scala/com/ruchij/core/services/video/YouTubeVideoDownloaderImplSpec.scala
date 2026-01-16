package com.ruchij.core.services.video

import cats.effect.IO
import cats.effect.std.Dispatcher
import com.ruchij.core.exceptions.CliCommandException
import com.ruchij.core.services.cli.{CliCommandRunner, CliCommandRunnerImpl}
import com.ruchij.core.services.video.models.YTDataUnit.MiB
import com.ruchij.core.services.video.models.{YTDataSize, YTDownloaderProgress}
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import fs2.Stream
import org.http4s.client.Client
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import com.ruchij.core.exceptions.{ResourceNotFoundException, UnsupportedVideoUrlException}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps

class YouTubeVideoDownloaderImplSpec extends AnyFlatSpec with MockFactory with Matchers {

  "videoInformation(Uri)" should "return video metadata information for the URI" in runIO {
    val cliCommandRunner = mock[CliCommandRunner[IO]]
    val client = mock[Client[IO]]
    val youTubeVideoDownloader = new YouTubeVideoDownloaderImpl[IO](cliCommandRunner, client)

    val cliOutput =
      """{
        |  "id": "4PrO20ALoCA",
        |  "title": "Silicon Chips Are So Yesterday - The Future is Plastic Chips!",
        |  "formats": [
        |     {
        |       "asr": 48000,
        |       "filesize": 3032308,
        |       "format_id": "249",
        |       "format_note": "tiny"
        |     },
        |     {
        |       "asr": null,
        |       "filesize": 82392719,
        |       "format_id": "399",
        |       "format_note": "1080p"
        |     },
        |     {
        |       "asr": 44100,
        |       "filesize": null,
        |       "format_id": "22",
        |       "format_note": "720p"
        |     }
        |   ],
        |   "upload_date": "20210805",
        |   "uploader": "Gary Explains",
        |   "uploader_id": "UCRjSO-juFtngAeJGJRMdIZw",
        |   "channel_id": "UCRjSO-juFtngAeJGJRMdIZw",
        |   "channel_url": "https://www.youtube.com/channel/UCRjSO-juFtngAeJGJRMdIZw",
        |   "duration": 466,
        |   "view_count": 52797,
        |   "channel": "Gary Explains",
        |   "extractor": "youtube",
        |   "webpage_url_basename": "watch",
        |   "extractor_key": "Youtube",
        |   "playlist": null,
        |   "playlist_index": null,
        |   "thumbnail": "https://i.ytimg.com/vi_webp/4PrO20ALoCA/maxresdefault.webp",
        |   "display_id": "4PrO20ALoCA"
        |}""".stripMargin

    (cliCommandRunner.run _)
      .expects("""yt-dlp --no-warnings "https://www.youtube.com/watch?v=4PrO20ALoCA" -j""")
      .returns(Stream.emits[IO, String](cliOutput.split("\n")))

    youTubeVideoDownloader
      .videoInformation(uri"https://www.youtube.com/watch?v=4PrO20ALoCA")
      .flatMap { videoAnalysisResult =>
        IO.delay {
          videoAnalysisResult.url mustBe uri"https://www.youtube.com/watch?v=4PrO20ALoCA"
          videoAnalysisResult.duration mustBe FiniteDuration(466, TimeUnit.SECONDS)
          videoAnalysisResult.title mustBe "Silicon Chips Are So Yesterday - The Future is Plastic Chips!"
          videoAnalysisResult.size mustBe 82392719
          videoAnalysisResult.thumbnail mustBe uri"https://i.ytimg.com/vi_webp/4PrO20ALoCA/maxresdefault.webp"
        }
      }
  }

  "supportedSites" should "return a list of supported video sites" in runIO {
    val cliCommandRunner = mock[CliCommandRunner[IO]]
    val client = mock[Client[IO]]
    val youTubeVideoDownloader = new YouTubeVideoDownloaderImpl[IO](cliCommandRunner, client)

    val cliOutput =
      """9now.com.au
        |abc.net.au
        |youtube
        |XHamster
        |""".stripMargin

    (cliCommandRunner.run _)
      .expects("yt-dlp --no-warnings --list-extractors")
      .returns {
        Stream.emits[IO, String] { cliOutput.split("\n").filter(_.nonEmpty) }
      }

    youTubeVideoDownloader.supportedSites
      .flatMap { sites =>
        IO.delay {
          sites mustBe Seq("9now.com.au", "abc.net.au", "youtube", "XHamster")
        }
      }
  }

  "downloadVideo(Uri, String, Stream[F, Boolean])" should "download the video to the file path" in runIO {
    val cliCommandRunner = mock[CliCommandRunner[IO]]
    val client = mock[Client[IO]]
    val youTubeVideoDownloader = new YouTubeVideoDownloaderImpl[IO](cliCommandRunner, client)

    val cliOutput =
      """[youtube] F1Zl1TRDJs0: Downloading webpage
        |[download] Destination: /home/ruchira/Videos/youtube-video-url-hash.mp4.f248
        |[download]   0.3% of   180.0MiB at     6.1MiB/s ETA 08:32
        |[download]  33.1% of 180.0MiB at  6.0MiB/s ETA 00:21
        |[download]  71.1% of 180.0MiB at  6.5MiB/s ETA 00:06
        |[download]  80.3% of 180.0MiB at  6.1MiB/s ETA 00:04
        |[download]  100% of 180.0MiB at  6.0MiB/s ETA 00:00
        |[download] Destination: /home/ruchira/Videos/youtube-video-url-hash.mp4.f140
        |[download]   0.0% of 12.91MiB at 350.96KiB/s ETA 00:37
        |[download]  15.5% of 12.91MiB at  5.89MiB/s ETA 00:01
        |[download]  75.1% of 12.91MiB at  1.23MiB/s ETA 00:02
        |[download] 100.0% of 12.91MiB at  5.91MiB/s ETA 00:00
        |[download] 100% of 12.91MiB in 00:02
        |[ffmpeg] Merging formats into "/home/ruchira/Videos/youtube-video-url-hash.mp4"
        |Deleting original file /home/ruchira/Videos/youtube-video-url-hash.mp4.f248 (pass -k to keep)
        |Deleting original file /home/ruchira/Videos/youtube-video-url-hash.mp4.f140 (pass -k to keep)
        |""".stripMargin

    (cliCommandRunner.run _)
      .expects(
        """yt-dlp --no-warnings -o "~/Videos/youtube-video-url-hash.%(ext)s" "https://www.youtube.com/watch?v=F1Zl1TRDJs0""""
      )
      .returns {
        Stream.emits[IO, String] { cliOutput.split("\n") }
      }

    youTubeVideoDownloader
      .downloadVideo(uri"https://www.youtube.com/watch?v=F1Zl1TRDJs0", "~/Videos/youtube-video-url-hash")
      .compile
      .toVector
      .flatMap { bytes =>
        IO.delay {
          bytes mustBe
            List(
              YTDownloaderProgress(0.0, YTDataSize(0.0, MiB), YTDataSize(0.0, MiB), 0 seconds),
              YTDownloaderProgress(0.3, YTDataSize(180.0, MiB), YTDataSize(6.1, MiB), 512 seconds),
              YTDownloaderProgress(33.1, YTDataSize(180.0, MiB), YTDataSize(6.0, MiB), 21 seconds),
              YTDownloaderProgress(71.1, YTDataSize(180.0, MiB), YTDataSize(6.5, MiB), 6 seconds),
              YTDownloaderProgress(80.3, YTDataSize(180.0, MiB), YTDataSize(6.1, MiB), 4 seconds),
              YTDownloaderProgress(100.0, YTDataSize(180.0, MiB), YTDataSize(6.0, MiB), 0 seconds),
              YTDownloaderProgress(100.0, YTDataSize(180.0, MiB), YTDataSize(6.0, MiB), 0 seconds),
              YTDownloaderProgress(100.0, YTDataSize(180.0, MiB), YTDataSize(6.0, MiB), 0 seconds),
              YTDownloaderProgress(100.0, YTDataSize(180.0, MiB), YTDataSize(6.0, MiB), 0 seconds),
              YTDownloaderProgress(100.0, YTDataSize(180.0, MiB), YTDataSize(6.0, MiB), 0 seconds)
            )
        }
      }
  }

  it should "throw an error attempting to download a non-existing video" in runIO {
    Dispatcher.parallel[IO].use { dispatcher =>
      val cliCommandRunner = new CliCommandRunnerImpl[IO](dispatcher)
      val client = mock[Client[IO]]

      val youTubeVideoDownloaderImpl = new YouTubeVideoDownloaderImpl[IO](cliCommandRunner, client)

      youTubeVideoDownloaderImpl.downloadVideo(
        uri"https://www.eporner.com/video-kb38QBpRQaY/small-town-girl-cheerleader-kait-gets-double-dick/",
        "$PWD/video-file"
      )
        .compile
        .drain
        .error
        .flatMap { exception =>
          IO.delay {
            exception mustBe a [CliCommandException]
            exception.getMessage contains "ERROR: [Eporner] kb38QBpRQaY: Unable to extract hash; please report this issue on  https://github.com/yt-dlp/yt-dlp/issues?q= , filling out the appropriate issue template. Confirm you are on the latest version using  yt-dlp -U"
          }
        }
    }
  }

  it should "return the yt-dlp version" in runIO {
    val cliCommandRunner = mock[CliCommandRunner[IO]]
    val client = mock[Client[IO]]
    val youTubeVideoDownloader = new YouTubeVideoDownloaderImpl[IO](cliCommandRunner, client)

    (cliCommandRunner.run _)
      .expects(
        """yt-dlp --version"""
      )
      .returns {
        Stream.emit[IO, String] {  "2025.08.22" }
      }

    youTubeVideoDownloader.version.flatMap { version =>
      IO.delay {
        version mustBe "2025.08.22"
      }
    }
  }

  it should "throw ResourceNotFoundException for 404 error" in runIO {
    val cliCommandRunner = mock[CliCommandRunner[IO]]
    val client = mock[Client[IO]]
    val youTubeVideoDownloader = new YouTubeVideoDownloaderImpl[IO](cliCommandRunner, client)

    (cliCommandRunner.run _)
      .expects("""yt-dlp --no-warnings "https://www.youtube.com/watch?v=notfound" -j""")
      .returns {
        Stream.raiseError[IO](CliCommandException("ERROR: HTTP Error 404: Not Found"))
      }

    youTubeVideoDownloader
      .videoInformation(uri"https://www.youtube.com/watch?v=notfound")
      .attempt
      .flatMap { result =>
        IO.delay {
          result.isLeft mustBe true
          result.left.exists(_.isInstanceOf[ResourceNotFoundException]) mustBe true
        }
      }
  }

  it should "throw ResourceNotFoundException for deleted video" in runIO {
    val cliCommandRunner = mock[CliCommandRunner[IO]]
    val client = mock[Client[IO]]
    val youTubeVideoDownloader = new YouTubeVideoDownloaderImpl[IO](cliCommandRunner, client)

    (cliCommandRunner.run _)
      .expects("""yt-dlp --no-warnings "https://www.youtube.com/watch?v=deleted" -j""")
      .returns {
        Stream.raiseError[IO](CliCommandException("ERROR: Unable to extract hash; some details"))
      }

    youTubeVideoDownloader
      .videoInformation(uri"https://www.youtube.com/watch?v=deleted")
      .attempt
      .flatMap { result =>
        IO.delay {
          result.isLeft mustBe true
          result.left.exists(_.isInstanceOf[ResourceNotFoundException]) mustBe true
        }
      }
  }

  it should "throw UnsupportedVideoUrlException for invalid JSON response" in runIO {
    val cliCommandRunner = mock[CliCommandRunner[IO]]
    val client = mock[Client[IO]]
    val youTubeVideoDownloader = new YouTubeVideoDownloaderImpl[IO](cliCommandRunner, client)

    (cliCommandRunner.run _)
      .expects("""yt-dlp --no-warnings "https://www.youtube.com/watch?v=invalid" -j""")
      .returns {
        Stream.emit[IO, String]("not valid json")
      }

    youTubeVideoDownloader
      .videoInformation(uri"https://www.youtube.com/watch?v=invalid")
      .attempt
      .flatMap { result =>
        IO.delay {
          result.isLeft mustBe true
          result.left.exists(_.isInstanceOf[UnsupportedVideoUrlException]) mustBe true
        }
      }
  }

  it should "propagate other CLI exceptions" in runIO {
    val cliCommandRunner = mock[CliCommandRunner[IO]]
    val client = mock[Client[IO]]
    val youTubeVideoDownloader = new YouTubeVideoDownloaderImpl[IO](cliCommandRunner, client)

    (cliCommandRunner.run _)
      .expects("""yt-dlp --no-warnings "https://www.youtube.com/watch?v=error" -j""")
      .returns {
        Stream.raiseError[IO](CliCommandException("ERROR: Some other error"))
      }

    youTubeVideoDownloader
      .videoInformation(uri"https://www.youtube.com/watch?v=error")
      .attempt
      .flatMap { result =>
        IO.delay {
          result.isLeft mustBe true
          result.left.exists(_.isInstanceOf[CliCommandException]) mustBe true
        }
      }
  }

  it should "use placeholder thumbnail when metadata has no thumbnail and retrieval fails" in runIO {
    import cats.effect.Resource
    import org.http4s.Request

    val cliCommandRunner = mock[CliCommandRunner[IO]]

    // Create a mock client that fails
    val client: Client[IO] = Client { (_: Request[IO]) =>
      Resource.eval(IO.raiseError(new RuntimeException("Failed to fetch")))
    }

    val youTubeVideoDownloader = new YouTubeVideoDownloaderImpl[IO](cliCommandRunner, client)

    val cliOutput =
      """{
        |  "id": "test123",
        |  "title": "Test Video Without Thumbnail",
        |  "extractor": "youtube",
        |  "formats": [{"filesize": 1000}],
        |  "duration": 120,
        |  "thumbnail": null
        |}""".stripMargin

    (cliCommandRunner.run _)
      .expects("""yt-dlp --no-warnings "https://www.youtube.com/watch?v=test123" -j""")
      .returns(Stream.emits[IO, String](cliOutput.split("\n")))

    youTubeVideoDownloader
      .videoInformation(uri"https://www.youtube.com/watch?v=test123")
      .flatMap { videoAnalysisResult =>
        IO.delay {
          videoAnalysisResult.title mustBe "Test Video Without Thumbnail"
          videoAnalysisResult.thumbnail.toString must include("video-placeholder.png")
        }
      }
  }

  it should "handle video with no filesize in formats" in runIO {
    val cliCommandRunner = mock[CliCommandRunner[IO]]
    val client = mock[Client[IO]]
    val youTubeVideoDownloader = new YouTubeVideoDownloaderImpl[IO](cliCommandRunner, client)

    val cliOutput =
      """{
        |  "id": "nosize",
        |  "title": "Video Without Size",
        |  "extractor": "youtube",
        |  "formats": [{"format_id": "1"}, {"format_id": "2"}],
        |  "duration": 60,
        |  "thumbnail": "https://example.com/thumb.jpg"
        |}""".stripMargin

    (cliCommandRunner.run _)
      .expects("""yt-dlp --no-warnings "https://www.youtube.com/watch?v=nosize" -j""")
      .returns(Stream.emits[IO, String](cliOutput.split("\n")))

    youTubeVideoDownloader
      .videoInformation(uri"https://www.youtube.com/watch?v=nosize")
      .flatMap { videoAnalysisResult =>
        IO.delay {
          videoAnalysisResult.title mustBe "Video Without Size"
          videoAnalysisResult.size mustBe 0
        }
      }
  }

}
