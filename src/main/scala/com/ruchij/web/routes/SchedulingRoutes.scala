package com.ruchij.web.routes

import cats.effect.Sync
import cats.implicits._
import com.ruchij.services.scheduling.SchedulingService
import com.ruchij.web.requests.SchedulingRequest
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl

object SchedulingRoutes {
  def apply[F[_]: Sync](schedulingService: SchedulingService[F])(implicit dsl: Http4sDsl[F]): HttpRoutes[F] = {
    import dsl._

    HttpRoutes.of[F] {
      case request @ POST -> Root =>
        for {
          scheduleRequest <- request.as[SchedulingRequest]
          scheduledVideoDownload <- schedulingService.schedule(scheduleRequest.uri)

          response <- Ok()
        }
        yield ???
    }
  }
}
