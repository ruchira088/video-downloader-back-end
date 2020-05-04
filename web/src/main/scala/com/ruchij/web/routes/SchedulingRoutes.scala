package com.ruchij.web.routes

import cats.effect.Sync
import cats.implicits._
import com.ruchij.circe.Encoders._
import com.ruchij.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.services.scheduling.SchedulingService
import com.ruchij.web.requests.SchedulingRequest
import com.ruchij.web.requests.queryparams.QueryParameter.SearchQuery
import com.ruchij.web.responses.SearchResult
import io.circe.Encoder
import io.circe.generic.auto._
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.circe._
import org.http4s.{HttpRoutes, ServerSentEvent}
import org.http4s.dsl.Http4sDsl

object SchedulingRoutes {
  def apply[F[_]: Sync](schedulingService: SchedulingService[F])(implicit dsl: Http4sDsl[F]): HttpRoutes[F] = {
    import dsl._

    HttpRoutes.of[F] {
      case request @ POST -> Root =>
        for {
          scheduleRequest <- request.as[SchedulingRequest]
          scheduledVideoDownload <- schedulingService.schedule(scheduleRequest.url)

          response <- Ok(scheduledVideoDownload)
        }
        yield response

      case GET -> Root / "search" :? queryParameters =>
        for {
          SearchQuery(term, pageSize, pageNumber) <- SearchQuery.fromQueryParameters[F].run(queryParameters)

          scheduledVideoDownloads <- schedulingService.search(term, pageNumber, pageSize)

          response <- Ok(SearchResult(scheduledVideoDownloads, pageNumber, pageSize, term))
        }
        yield response

      case GET -> Root / "active" =>
        Ok {
          schedulingService.active
            .map {
              scheduledVideoDownload =>
                ServerSentEvent {
                  Encoder[ScheduledVideoDownload].apply(scheduledVideoDownload).noSpaces
                }
            }
        }
    }
  }
}
