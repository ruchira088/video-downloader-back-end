package com.ruchij.web

import cats.effect.{Blocker, Concurrent, ContextShift, Timer}
import cats.implicits._
import com.ruchij.services.asset.AssetService
import com.ruchij.services.health.HealthService
import com.ruchij.services.scheduling.SchedulingService
import com.ruchij.services.video.{VideoAnalysisService, VideoService}
import com.ruchij.web.middleware.{ExceptionHandler, NotFoundHandler}
import com.ruchij.web.routes.{AssetRoutes, SchedulingRoutes, ServiceRoutes, VideoRoutes, WebServerRoutes}
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.http4s.server.middleware.CORS
import org.http4s.{HttpApp, HttpRoutes}

object Routes {
  def apply[F[_]: Concurrent: Timer: ContextShift](
    videoService: VideoService[F],
    videoAnalysisService: VideoAnalysisService[F],
    schedulingService: SchedulingService[F],
    assetService: AssetService[F],
    healthService: HealthService[F],
    ioBlocker: Blocker
  ): HttpApp[F] = {
    implicit val dsl: Http4sDsl[F] = new Http4sDsl[F] {}

    val routes: HttpRoutes[F] =
      WebServerRoutes(ioBlocker) <+>
        Router(
          "/schedule" -> SchedulingRoutes(schedulingService),
          "/videos" -> VideoRoutes(videoService, videoAnalysisService),
          "/assets" -> AssetRoutes(assetService),
          "/service" -> ServiceRoutes(healthService),
        )

    CORS {
      ExceptionHandler {
        NotFoundHandler(routes)
      }
    }
  }
}
