package com.ruchij.core.services.video

import cats.effect.IO
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.services.download.DownloadService
import com.ruchij.core.services.hashing.HashingService
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.FunctionKTypes.identityFunctionK
import org.http4s.implicits.http4sLiteralsSyntax
import org.http4s.jdkhttpclient.JdkHttpClient
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.net.http.HttpClient
import java.net.http.HttpClient.Redirect

class VideoAnalysisServiceImplSpec extends AnyFlatSpec with MockFactory with Matchers {

  "analyze(uri: Uri)" should "return metadata results for the video URL" in runIO {
    IO.blocking(HttpClient.newBuilder().followRedirects(Redirect.NORMAL).build())
      .flatMap { javaHttpClient =>
        JdkHttpClient[IO](javaHttpClient).use { httpClient =>
          val hashingService = mock[HashingService[IO]]
          val downloadService = mock[DownloadService[IO]]
          val youTubeVideoDownloader = mock[YouTubeVideoDownloader[IO]]
          val videoMetadataDao = mock[VideoMetadataDao[IO]]
          val fileResourceDao = mock[FileResourceDao[IO]]
          val storageConfiguration = mock[StorageConfiguration]

          val videoAnalysisServiceImpl =
            new VideoAnalysisServiceImpl[IO, IO](
              hashingService,
              downloadService,
              youTubeVideoDownloader,
              httpClient,
              videoMetadataDao,
              fileResourceDao,
              storageConfiguration
            )

          for {
            result <- videoAnalysisServiceImpl.analyze(uri"https://spankbang.com/4n8r4/video/hot+mature")
            _ <- IO.blocking(println(result))
          } yield (): Unit
        }
      }
  }

}
