package com.ruchij

import java.nio.file.Paths
import java.text.DecimalFormat

import cats.effect.{Blocker, ExitCode, IO, IOApp}
import cats.implicits._
import fs2.Stream
import com.ruchij.config.DownloadConfiguration
import com.ruchij.services.download.Http4sDownloadService
import com.ruchij.services.download.models.DownloadResult
import com.ruchij.services.video.VideoAnalysisServiceImpl
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.implicits._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

object WebSandbox extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    BlazeClientBuilder[IO](ExecutionContext.global).resource
      .use { client =>
//        val downloadService =
//          new Http4sDownloadService[IO](
//            client,
//            Blocker.liftExecutionContext(ExecutionContext.global),
//            DownloadConfiguration(Paths.get("/Users/ruchira/Development/video-downloader"))
//          )
//
//        downloadService.download(uri"https://cdn-us5.vporn.com/vid2/_9e-jeXmKbURH25h6xy1SQ/1585321518/s279-s281/68/277000968/277000968_720x406_500k.mp4")
//          .use {
//            case DownloadResult(path, size, data) =>
//              IO.delay(println(s"Saving file at $path"))
//                .productR {
//                  data.map(_ / size.toDouble * 100)
//                    .evalMap(percentage => IO.delay(println(percentage)))
//                    .compile
//                    .drain
//                }
//
//          }
        val videoService = new VideoAnalysisServiceImpl[IO](client)

        videoService.metadata(uri"https://www.vporn.com/model/female-friendly-interracial-sex/276970990/")
          .flatMap(videoMetadata => IO.delay(println(videoMetadata)))
      }
      .as(ExitCode.Success)
}
