package com.ruchij.api.test.mixins

import cats.effect.{Blocker, Concurrent, ContextShift, Timer}
import com.ruchij.api.services.authentication.AuthenticationService
import com.ruchij.api.services.health.HealthService
import com.ruchij.api.web.Routes
import com.ruchij.core.messaging.Publisher
import com.ruchij.core.messaging.models.HttpMetric
import com.ruchij.core.services.asset.AssetService
import com.ruchij.core.services.scheduling.SchedulingService
import com.ruchij.core.services.scheduling.models.DownloadProgress
import com.ruchij.core.services.video.{VideoAnalysisService, VideoService}
import fs2.Stream
import org.http4s.HttpApp
import org.scalamock.scalatest.MockFactory

import scala.concurrent.ExecutionContext

trait MockedRoutes[F[+ _]] { self: MockFactory =>

  val videoService: VideoService[F] = mock[VideoService[F]]
  val videoAnalysisService: VideoAnalysisService[F] = mock[VideoAnalysisService[F]]
  val schedulingService: SchedulingService[F] = mock[SchedulingService[F]]
  val assetService: AssetService[F] = mock[AssetService[F]]
  val healthService: HealthService[F] = mock[HealthService[F]]
  val authenticationService: AuthenticationService[F] = mock[AuthenticationService[F]]
  val downloadProgressStream: Stream[F, DownloadProgress] = Stream.empty
  val metricPublisher: Publisher[F, HttpMetric] = mock[Publisher[F, HttpMetric]]

  val blockerIO: Blocker = Blocker.liftExecutionContext(ExecutionContext.global)

  val timer: Timer[F] = mock[Timer[F]]
  val contextShift: ContextShift[F]
  val concurrent: Concurrent[F]

  def createRoutes(): HttpApp[F] = Routes(
    videoService,
    videoAnalysisService,
    schedulingService,
    assetService,
    healthService,
    authenticationService,
    downloadProgressStream,
    metricPublisher,
    blockerIO
  )(concurrent, timer, contextShift)

}
