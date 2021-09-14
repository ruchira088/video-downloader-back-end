package com.ruchij.api.web.routes

import cats.effect.{Concurrent, Timer}
import cats.implicits._
import com.ruchij.api.services.scheduling.ApiSchedulingService
import com.ruchij.api.web.requests.{SchedulingRequest, UpdateScheduledVideoRequest, WorkerStatusUpdateRequest}
import com.ruchij.api.web.requests.UpdateScheduledVideoRequest.updateScheduledVideoRequestValidator
import com.ruchij.api.web.requests.RequestOps.RequestOpsSyntax
import com.ruchij.api.web.requests.queryparams.SearchQuery
import com.ruchij.core.services.scheduling.models.DownloadProgress
import com.ruchij.core.types.JodaClock
import com.ruchij.api.web.responses.EventStreamEventType.{ActiveDownload, HeartBeat}
import com.ruchij.core.circe.Decoders._
import com.ruchij.core.circe.Encoders._
import com.ruchij.api.web.responses.{EventStreamHeartBeat, SearchResult, WorkerStatusResponse}
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
  def apply[F[+ _]: Concurrent: Timer](
    apiSchedulingService: ApiSchedulingService[F],
    downloadProgressStream: Stream[F, DownloadProgress]
  )(implicit dsl: Http4sDsl[F]): HttpRoutes[F] = {
    import dsl._

    HttpRoutes.of[F] {
      case request @ POST -> Root =>
        for {
          scheduleRequest <- request.as[SchedulingRequest]
          scheduledVideoDownload <- apiSchedulingService.schedule(scheduleRequest.url)

          response <- Ok(scheduledVideoDownload)
        } yield response

      case GET -> Root / "search" :? queryParameters =>
        for {
          SearchQuery(term, statuses, durationRange, sizeRange, videoUrls, videoSites, pageSize, pageNumber, sortBy, order) <- SearchQuery
            .fromQueryParameters[F]
            .run(queryParameters)

          scheduledVideoDownloads <- apiSchedulingService.search(
            term,
            videoUrls,
            durationRange,
            sizeRange,
            pageNumber,
            pageSize,
            sortBy,
            order,
            statuses,
            videoSites
          )

          response <- Ok {
            SearchResult(
              scheduledVideoDownloads,
              pageNumber,
              pageSize,
              term,
              videoUrls,
              statuses,
              durationRange,
              sizeRange,
              sortBy,
              order
            )
          }
        } yield response

      case GET -> Root / "id" / videoId =>
        apiSchedulingService
          .getById(videoId)
          .flatMap { scheduledVideoDownload =>
            Ok(scheduledVideoDownload)
          }

      case request @ PUT -> Root / "id" / videoId =>
        for {
          UpdateScheduledVideoRequest(schedulingStatus) <- request.to[UpdateScheduledVideoRequest]

          updatedScheduledVideoDownload <- apiSchedulingService.updateSchedulingStatus(videoId, schedulingStatus)

          response <- Ok(updatedScheduledVideoDownload)
        } yield response

      case GET -> Root / "active" =>
        Ok {
          downloadProgressStream
            .map { downloadProgress =>
              ServerSentEvent(Some(Encoder[DownloadProgress].apply(downloadProgress).noSpaces), ActiveDownload)
            }
            .merge {
              Stream
                .fixedRate[F](10 seconds)
                .zipRight(Stream.eval(JodaClock[F].timestamp).repeat)
                .map { timestamp =>
                  ServerSentEvent(Some(EventStreamHeartBeat(timestamp).asJson.noSpaces), HeartBeat)
                }
            }
        }

      case GET -> Root / "worker-status" =>
        apiSchedulingService.getWorkerStatus.flatMap {
          workerStatus => Ok(WorkerStatusResponse(workerStatus))
        }


      case request @ PUT -> Root / "worker-status" =>
        for {
          workerStatusUpdateRequest <- request.to[WorkerStatusUpdateRequest]

          _ <- apiSchedulingService.updateWorkerStatus(workerStatusUpdateRequest.workerStatus)

          response <- Ok(WorkerStatusResponse(workerStatusUpdateRequest.workerStatus))
        } yield response
    }
  }
}
