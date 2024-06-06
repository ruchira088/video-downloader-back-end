package com.ruchij.api.test.mixins

import cats.effect.kernel.Async
import com.ruchij.api.services.authentication.AuthenticationService
import com.ruchij.api.services.health.HealthService
import com.ruchij.api.services.playlist.PlaylistService
import com.ruchij.api.services.scheduling.ApiSchedulingService
import com.ruchij.api.services.user.UserService
import com.ruchij.api.services.video.ApiVideoService
import com.ruchij.api.web.Routes
import com.ruchij.core.messaging.Publisher
import com.ruchij.core.messaging.models.HttpMetric
import com.ruchij.core.services.asset.AssetService
import com.ruchij.core.services.scheduling.models.DownloadProgress
import com.ruchij.core.services.video.VideoAnalysisService
import com.ruchij.core.types.JodaClock
import fs2.Stream
import fs2.compression.Compression
import org.http4s.HttpApp
import org.scalamock.scalatest.MockFactory
import org.scalatest.{OneInstancePerTest, TestSuite}

trait MockedRoutes[F[_]] extends MockFactory with OneInstancePerTest { self: TestSuite =>

  val userService: UserService[F] = mock[UserService[F]]
  val apiVideoService: ApiVideoService[F] = mock[ApiVideoService[F]]
  val playlistService: PlaylistService[F] = mock[PlaylistService[F]]
  val videoAnalysisService: VideoAnalysisService[F] = mock[VideoAnalysisService[F]]
  val apiSchedulingService: ApiSchedulingService[F] = mock[ApiSchedulingService[F]]
  val assetService: AssetService[F] = mock[AssetService[F]]
  val healthService: HealthService[F] = mock[HealthService[F]]
  val authenticationService: AuthenticationService[F] = mock[AuthenticationService[F]]
  val downloadProgressStream: Stream[F, DownloadProgress] = Stream.empty
  val metricPublisher: Publisher[F, HttpMetric] = mock[Publisher[F, HttpMetric]]

  val async: Async[F]
  val jodaClock: JodaClock[F]
  val compression: Compression[F]

  def createRoutes(): HttpApp[F] =
    Routes(
      userService,
      apiVideoService,
      videoAnalysisService,
      apiSchedulingService,
      playlistService,
      assetService,
      healthService,
      authenticationService,
      downloadProgressStream,
      metricPublisher,
    )(async, jodaClock, compression)

  def ignoreHttpMetrics(): F[Unit] =
    async.delay {
      (metricPublisher.publishOne _).expects(*).returns(async.unit)
    }
}
