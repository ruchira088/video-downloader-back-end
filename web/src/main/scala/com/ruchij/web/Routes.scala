package com.ruchij.web

import cats.effect.{Concurrent, Timer}
import com.ruchij.services.asset.AssetService
import com.ruchij.services.health.HealthService
import com.ruchij.services.scheduling.SchedulingService
import com.ruchij.services.video.VideoService
import com.ruchij.web.middleware.{ExceptionHandler, NotFoundHandler}
import com.ruchij.web.routes.{AssetRoutes, SchedulingRoutes, ServiceRoutes, VideoRoutes}
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.http4s.server.middleware.CORS
import org.http4s.{HttpApp, HttpRoutes}

object Routes {
  def apply[F[_]: Concurrent: Timer](
    videoService: VideoService[F],
    schedulingService: SchedulingService[F],
    assetService: AssetService[F],
    healthService: HealthService[F]
  ): HttpApp[F] = {
    implicit val dsl: Http4sDsl[F] = new Http4sDsl[F] {}

    val routes: HttpRoutes[F] =
      Router(
        "/schedule" -> SchedulingRoutes(schedulingService),
        "/videos" -> VideoRoutes(videoService),
        "/assets" -> AssetRoutes(assetService),
        "/service" -> ServiceRoutes(healthService)
      )

    CORS {
      ExceptionHandler {
        NotFoundHandler(routes)
      }
    }
  }
}
