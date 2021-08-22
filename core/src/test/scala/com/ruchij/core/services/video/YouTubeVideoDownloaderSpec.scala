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
      .expects("youtube-dl https://www.youtube.com/watch?v=4PrO20ALoCA -j", *)
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
}
