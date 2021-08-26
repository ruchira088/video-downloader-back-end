package com.ruchij.core.services.video

import cats.effect.IO
import com.ruchij.core.services.cli.CliCommandRunner
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.Providers.contextShift
import fs2.Stream
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration

class YouTubeVideoDownloaderSpec extends AnyFlatSpec with MockFactory with Matchers {

  "videoInformation(Uri)" should "return video metadata information for the URI" in runIO {
    val cliCommandRunner = mock[CliCommandRunner[IO]]
    val youTubeVideoDownloader = new YouTubeVideoDownloaderImpl[IO](cliCommandRunner)

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
      .expects("""youtube-dl "https://www.youtube.com/watch?v=4PrO20ALoCA" -j""", *)
      .returns(Stream.emits[IO, String](cliOutput.split("\n")))

    youTubeVideoDownloader.videoInformation(uri"https://www.youtube.com/watch?v=4PrO20ALoCA")
      .flatMap {
        videoAnalysisResult =>
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
    val youTubeVideoDownloader = new YouTubeVideoDownloaderImpl[IO](cliCommandRunner)

    val cliOutput =
      """9now.com.au
        |abc.net.au
        |youtube
        |XHamster
        |""".stripMargin

    (cliCommandRunner.run _).expects("youtube-dl --list-extractors", *)
      .returns {
        Stream.emits[IO, String] { cliOutput.split("\n").filter(_.nonEmpty) }
      }

    youTubeVideoDownloader.supportedSites
      .flatMap {
        sites => IO.delay {
          sites mustBe Seq("9now.com.au", "abc.net.au", "youtube", "XHamster")
        }
      }
  }

  "downloadVideo(Uri, String, Stream[F, Boolean])" should "download the video to the file path" in runIO {
    val cliCommandRunner = mock[CliCommandRunner[IO]]
    val youTubeVideoDownloader = new YouTubeVideoDownloaderImpl[IO](cliCommandRunner)

    val cliOutput =
      """[youtube] F1Zl1TRDJs0: Downloading webpage
        |[download] Destination: /home/ruchira/Videos/youtube-video-url-hash.mp4.f248
        |[download]  0.3% of 180.0MiB at  6.1MiB/s ETA 00:32
        |[download]  11.1% of 180.0MiB at  6.0MiB/s ETA 00:28
        |[download]  20.3% of 180.0MiB at  6.1MiB/s ETA 00:25
        |[download]  33.1% of 180.0MiB at  6.0MiB/s ETA 00:21
        |[download]  40.3% of 180.0MiB at  6.1MiB/s ETA 00:20
        |[download]  51.1% of 180.0MiB at  6.0MiB/s ETA 00:15
        |[download]  60.3% of 180.0MiB at  6.1MiB/s ETA 00:09
        |[download]  71.1% of 180.0MiB at  6.5MiB/s ETA 00:06
        |[download]  80.3% of 180.0MiB at  6.1MiB/s ETA 00:04
        |[download]  91.1% of 180.0MiB at  6.0MiB/s ETA 00:02
        |[download]  100% of 180.0MiB at  6.0MiB/s ETA 00:00
        |[download] Destination: /home/ruchira/Videos/youtube-video-url-hash.mp4.f140
        |[download]   0.0% of 12.91MiB at 350.96KiB/s ETA 00:37
        |[download]  15.5% of 12.91MiB at  5.89MiB/s ETA 00:01
        |[download]  31.0% of 12.91MiB at  5.91MiB/s ETA 00:01
        |[download]  62.0% of 12.91MiB at  5.92MiB/s ETA 00:00
        |[download]  75.1% of 12.91MiB at  5.83MiB/s ETA 00:00
        |[download]  75.1% of 12.91MiB at  1.23MiB/s ETA 00:02
        |[download]  90.6% of 12.91MiB at  5.86MiB/s ETA 00:00
        |[download] 100.0% of 12.91MiB at  5.91MiB/s ETA 00:00
        |[download] 100% of 12.91MiB in 00:02
        |[ffmpeg] Merging formats into "/home/ruchira/Videos/youtube-video-url-hash.mp4"
        |Deleting original file /home/ruchira/Videos/youtube-video-url-hash.mp4.f248 (pass -k to keep)
        |Deleting original file /home/ruchira/Videos/youtube-video-url-hash.mp4.f140 (pass -k to keep)
        |""".stripMargin

    (cliCommandRunner.run _).expects("""youtube-dl -o "~/Videos/youtube-video-url-hash.mp4" --merge-output-format mp4 "https://www.youtube.com/watch?v=F1Zl1TRDJs0"""", *)
      .returns {
        Stream.emits[IO, String] { cliOutput.split("\n") }
      }

    IO.delay(Paths.get("~/Videos/youtube-video-url-hash.mp4"))
      .flatMap { filePath =>
        youTubeVideoDownloader
          .downloadVideo(uri"https://www.youtube.com/watch?v=F1Zl1TRDJs0", filePath, Stream.never[IO])
          .compile
          .toVector
      }
      .flatMap { bytes =>
        IO.delay {
          bytes mustBe
            Seq(
              0, 566231, 20950548, 38314967, 62474158, 76063703, 96448020, 113812439, 134196756, 151561175,
              171945492, 188743680, 188743680, 188743680, 188743680, 188743680, 188743680, 188743680,
              188743680, 188743680
            )
        }
      }
  }

}
