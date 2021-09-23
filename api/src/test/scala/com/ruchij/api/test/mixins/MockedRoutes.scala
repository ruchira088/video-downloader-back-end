package com.ruchij.api.test.mixins

import cats.effect.{Blocker, Clock, Concurrent, ContextShift, Timer}
import cats.implicits._
import com.ruchij.api.services.authentication.AuthenticationService
import com.ruchij.api.services.health.HealthService
import com.ruchij.api.services.playlist.PlaylistService
import com.ruchij.api.services.scheduling.ApiSchedulingService
import com.ruchij.api.services.user.UserService
import com.ruchij.api.web.Routes
import com.ruchij.core.messaging.Publisher
import com.ruchij.core.messaging.models.HttpMetric
import com.ruchij.core.services.asset.AssetService
import com.ruchij.core.services.scheduling.models.DownloadProgress
import com.ruchij.core.services.video.{VideoAnalysisService, VideoService}
import fs2.Stream
import org.http4s.HttpApp
import org.scalamock.scalatest.MockFactory
import org.scalatest.OneInstancePerTest

import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext

trait MockedRoutes[F[+ _]] extends MockFactory with OneInstancePerTest {

  val userService: UserService[F] = mock[UserService[F]]
  val videoService: VideoService[F] = mock[VideoService[F]]
  val playlistService: PlaylistService[F] = mock[PlaylistService[F]]
  val videoAnalysisService: VideoAnalysisService[F] = mock[VideoAnalysisService[F]]
  val apiSchedulingService: ApiSchedulingService[F] = mock[ApiSchedulingService[F]]
  val assetService: AssetService[F] = mock[AssetService[F]]
  val healthService: HealthService[F] = mock[HealthService[F]]
  val authenticationService: AuthenticationService[F] = mock[AuthenticationService[F]]
  val downloadProgressStream: Stream[F, DownloadProgress] = Stream.empty
  val metricPublisher: Publisher[F, HttpMetric] = mock[Publisher[F, HttpMetric]]

  val blockerIO: Blocker = Blocker.liftExecutionContext(ExecutionContext.global)

  val timer: Timer[F] = mock[Timer[F]]
  val contextShift: ContextShift[F]
  val concurrent: Concurrent[F]

  def createRoutes(): F[HttpApp[F]] =
    concurrent.delay {
      Routes(
        userService,
        videoService,
        videoAnalysisService,
        apiSchedulingService,
        playlistService,
        assetService,
        healthService,
        authenticationService,
        downloadProgressStream,
        metricPublisher,
        blockerIO
      )(concurrent, timer, contextShift)
    }


  def ignoreHttpMetrics(): F[Unit] = {
    val clock = mock[Clock[F]]
    (clock.realTime _).expects(TimeUnit.MILLISECONDS).returns(concurrent.pure(0)).repeat(2)

    catsSyntaxApply(concurrent.delay { (() => timer.clock).expects().returns(clock) })(concurrent)
      .productR {
        concurrent.delay {
          (metricPublisher.publishOne _).expects(*).returns(concurrent.unit)
        }
      }
  }
}
