package com.ruchij.core.services.video

import cats.effect.IO
import cats.implicits._
import com.ruchij.core.config.StorageConfiguration
import com.ruchij.core.daos.resource.FileResourceDao
import com.ruchij.core.daos.videometadata.VideoMetadataDao
import com.ruchij.core.external.TestExternalServiceProvider
import com.ruchij.core.external.containers.ContainerExternalServiceProvider
import com.ruchij.core.external.local.LocalExternalServiceProvider
import com.ruchij.core.services.download.DownloadService
import com.ruchij.core.services.hashing.HashingService
import com.ruchij.core.services.renderer.SpaSiteRendererImpl
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.FunctionKTypes.identityFunctionK
import org.http4s.implicits.http4sLiteralsSyntax
import org.http4s.jdkhttpclient.JdkHttpClient
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.net.http.HttpClient
import java.net.http.HttpClient.Redirect
import scala.jdk.CollectionConverters.MapHasAsScala

class VideoAnalysisServiceImplSpec extends AnyFlatSpec with MockFactory with Matchers {

  "analyze(uri: Uri)" should "return metadata results for the video URL" in runIO {
    IO.delay(System.getenv().asScala.toMap).flatMap { environmentVariables =>
      val externalServiceProvider =
        new TestExternalServiceProvider[IO](
          new LocalExternalServiceProvider[IO],
          new ContainerExternalServiceProvider[IO],
          environmentVariables
        )

      IO.blocking(HttpClient.newBuilder().followRedirects(Redirect.NORMAL).build())
        .flatMap { javaHttpClient =>
          externalServiceProvider.spaSiteRendererConfiguration
            .product(JdkHttpClient[IO](javaHttpClient))
            .use {
              case (spaSiteRendererConfiguration, httpClient) =>
                val hashingService = mock[HashingService[IO]]
                val downloadService = mock[DownloadService[IO]]
                val youTubeVideoDownloader = mock[YouTubeVideoDownloader[IO]]
                val videoMetadataDao = mock[VideoMetadataDao[IO]]
                val fileResourceDao = mock[FileResourceDao[IO]]
                val storageConfiguration = mock[StorageConfiguration]
                val spaSiteRenderer =
                  new SpaSiteRendererImpl[IO](httpClient, spaSiteRendererConfiguration)

                val videoAnalysisServiceImpl =
                  new VideoAnalysisServiceImpl[IO, IO](
                    hashingService,
                    downloadService,
                    youTubeVideoDownloader,
                    httpClient,
                    spaSiteRenderer,
                    videoMetadataDao,
                    fileResourceDao,
                    storageConfiguration
                  )

                val videoUrl =
                  uri"https://txxx.com/videos/18365405/blonde-gets-analized-by-a-black-cock-with-heather-gables/"

                for {
                  analysisResult <- videoAnalysisServiceImpl.analyze(videoUrl)
                  _ <- IO.blocking(println(analysisResult))

                  videoDownloadUrl <- videoAnalysisServiceImpl.downloadUri(videoUrl)
                  _ <- IO.blocking(println(videoDownloadUrl))

                } yield (): Unit
            }
        }

    }
  }

}
