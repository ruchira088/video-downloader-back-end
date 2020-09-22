package com.ruchij.web.routes

import java.util.concurrent.TimeUnit

import cats.effect.{Concurrent, Timer}
import cats.implicits._
import com.ruchij.circe.Encoders._
import com.ruchij.services.scheduling.SchedulingService
import com.ruchij.services.scheduling.models.DownloadProgress
import com.ruchij.types.JodaClock
import com.ruchij.web.requests.SchedulingRequest
import com.ruchij.web.requests.queryparams.SearchQuery
import com.ruchij.web.responses.{EventStreamEventType, EventStreamHeartBeat, SearchResult}
import fs2.Stream
import io.circe.Encoder
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.circe._
import org.http4s.{HttpRoutes, ServerSentEvent}
import org.http4s.dsl.Http4sDsl

import scala.concurrent.duration.FiniteDuration

object SchedulingRoutes {
  def apply[F[_]: Concurrent: Timer](schedulingService: SchedulingService[F])(implicit dsl: Http4sDsl[F]): HttpRoutes[F] = {
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
          SearchQuery(term, videoUrls, pageSize, pageNumber, sortBy, order) <- SearchQuery.fromQueryParameters[F].run(queryParameters)

          scheduledVideoDownloads <- schedulingService.search(term, videoUrls, pageNumber, pageSize, sortBy, order)

          response <- Ok(SearchResult(scheduledVideoDownloads, pageNumber, pageSize, term, videoUrls, sortBy, order))
        }
        yield response

      case GET -> Root / "videoId" / videoId =>
        schedulingService.getById(videoId).flatMap(scheduledVideoDownload => Ok(scheduledVideoDownload))

      case GET -> Root / "active" =>
        Ok {
          schedulingService.active
            .map {
              downloadProgress =>
                ServerSentEvent(
                  Encoder[DownloadProgress].apply(downloadProgress).noSpaces,
                  EventStreamEventType.ActiveDownload
                )
            }
            .merge {
              Stream.fixedRate[F](FiniteDuration(10, TimeUnit.SECONDS))
                .zipRight(Stream.eval(JodaClock[F].timestamp).repeat)
                .map {
                  timestamp =>
                    ServerSentEvent(
                      EventStreamHeartBeat(timestamp).asJson.noSpaces,
                      EventStreamEventType.HeartBeat
                    )
                }
            }
        }
    }
  }
}
