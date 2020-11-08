package com.ruchij.api.web.routes

import cats.effect.{Concurrent, Timer}
import cats.implicits._
import com.ruchij.api.web.requests.{SchedulingRequest, UpdateScheduledVideoRequest}
import com.ruchij.api.web.requests.UpdateScheduledVideoRequest.updateScheduledVideoRequestValidator
import com.ruchij.api.web.requests.RequestOps.RequestOpsSyntax
import com.ruchij.api.web.requests.queryparams.SearchQuery
import com.ruchij.core.services.scheduling.SchedulingService
import com.ruchij.core.services.scheduling.models.DownloadProgress
import com.ruchij.core.types.JodaClock
import com.ruchij.api.web.responses.EventStreamEventType.{ActiveDownload, HeartBeat}
import com.ruchij.api.circe.Decoders._
import com.ruchij.api.circe.Encoders._
import com.ruchij.api.web.responses.{EventStreamHeartBeat, SearchResult}
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.services.video.models.DurationRange
import fs2.Stream
import io.circe.Encoder
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.circe._
import org.http4s.{HttpRoutes, ServerSentEvent}
import org.http4s.dsl.Http4sDsl

import scala.concurrent.duration._
import scala.language.postfixOps

object SchedulingRoutes {
  def apply[F[_]: Concurrent: Timer](
    schedulingService: SchedulingService[F]
  )(implicit dsl: Http4sDsl[F]): HttpRoutes[F] = {
    import dsl._

    HttpRoutes.of[F] {
      case request @ POST -> Root =>
        for {
          scheduleRequest <- request.as[SchedulingRequest]
          scheduledVideoDownload <- schedulingService.schedule(scheduleRequest.url)

          response <- Ok(scheduledVideoDownload)
        } yield response

      case GET -> Root / "search" :? queryParameters =>
        for {
          SearchQuery(term, _, videoUrls, pageSize, pageNumber, sortBy, order) <- SearchQuery
            .fromQueryParameters[F]
            .run(queryParameters)

          scheduledVideoDownloads <- schedulingService.search(term, videoUrls, pageNumber, pageSize, sortBy, order, None)

          response <- Ok {
            SearchResult(
              scheduledVideoDownloads.map(_.asJson(valueWithProgress[ScheduledVideoDownload, Long])),
              pageNumber,
              pageSize,
              term,
              videoUrls,
              DurationRange.All,
              sortBy,
              order
            )
          }
        } yield response

      case GET -> Root / "videoId" / videoId =>
        schedulingService.getById(videoId)
          .flatMap {
            scheduledVideoDownload =>
              Ok(scheduledVideoDownload.asJson(valueWithProgress[ScheduledVideoDownload, Long]))
          }

      case request @ PUT -> Root / "videoId" / videoId =>
        for {
          UpdateScheduledVideoRequest(schedulingStatus) <- request.to[UpdateScheduledVideoRequest]

          updatedScheduledVideoDownload <- schedulingService.updateStatus(videoId, schedulingStatus)

          response <- Ok(updatedScheduledVideoDownload)
        } yield response

      case GET -> Root / "active" =>
        Ok {
          schedulingService.downloadProgress
            .map { downloadProgress =>
              ServerSentEvent(Encoder[DownloadProgress].apply(downloadProgress).noSpaces, ActiveDownload)
            }
            .merge {
              Stream
                .fixedRate[F](10 seconds)
                .zipRight(Stream.eval(JodaClock[F].timestamp).repeat)
                .map { timestamp =>
                  ServerSentEvent(EventStreamHeartBeat(timestamp).asJson.noSpaces, HeartBeat)
                }
            }
        }
    }
  }
}
