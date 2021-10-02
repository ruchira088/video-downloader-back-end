package com.ruchij.api.web.routes

import cats.effect.{Concurrent, Timer}
import cats.implicits._
import com.ruchij.api.services.models.Context.AuthenticatedRequestContext
import com.ruchij.api.services.scheduling.ApiSchedulingService
import com.ruchij.api.web.requests.{SchedulingRequest, UpdateScheduledVideoRequest, WorkerStatusUpdateRequest}
import com.ruchij.api.web.requests.UpdateScheduledVideoRequest.updateScheduledVideoRequestValidator
import com.ruchij.api.web.requests.RequestOps.ContextRequestOpsSyntax
import com.ruchij.api.web.requests.queryparams.SearchQuery
import com.ruchij.core.services.scheduling.models.DownloadProgress
import com.ruchij.core.types.JodaClock
import com.ruchij.api.web.responses.EventStreamEventType.{ActiveDownload, HeartBeat}
import com.ruchij.core.circe.Decoders._
import com.ruchij.core.circe.Encoders._
import com.ruchij.api.web.responses.{EventStreamHeartBeat, SearchResult, WorkerStatusResponse}
import com.ruchij.core.services.models.SortBy
import fs2.Stream
import io.circe.Encoder
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.circe._
import org.http4s.{ContextRoutes, ServerSentEvent}
import org.http4s.dsl.Http4sDsl

import scala.concurrent.duration._
import scala.language.postfixOps

object SchedulingRoutes {
  def apply[F[+ _]: Concurrent: Timer](
    apiSchedulingService: ApiSchedulingService[F],
    downloadProgressStream: Stream[F, DownloadProgress]
  )(implicit dsl: Http4sDsl[F]): ContextRoutes[AuthenticatedRequestContext, F] = {
    import dsl._

    ContextRoutes.of[AuthenticatedRequestContext, F] {
      case contextRequest @ POST -> Root as AuthenticatedRequestContext(user, requestId) =>
        for {
          scheduleRequest <- contextRequest.to[SchedulingRequest]
          scheduledVideoDownload <- apiSchedulingService.schedule(scheduleRequest.url.withoutFragment, user.id)

          response <- Ok(scheduledVideoDownload)
        } yield response

      case GET -> Root / "search" :? queryParameters as AuthenticatedRequestContext(user, requestId) =>
        for {
          SearchQuery(term, statuses, durationRange, sizeRange, videoUrls, videoSites, pagingQuery) <- SearchQuery
            .fromQueryParameters[F]
            .run(queryParameters)

          scheduledVideoDownloads <- apiSchedulingService.search(
            term,
            videoUrls.map(_.map(_.withoutFragment)),
            durationRange,
            sizeRange,
            pagingQuery.pageNumber,
            pagingQuery.pageSize,
            pagingQuery.maybeSortBy.getOrElse(SortBy.Date),
            pagingQuery.order,
            statuses,
            videoSites,
            user.nonAdminUserId
          )

          response <- Ok {
            SearchResult(
              scheduledVideoDownloads,
              pagingQuery.pageNumber,
              pagingQuery.pageSize,
              term,
              videoUrls,
              statuses,
              durationRange,
              sizeRange,
              pagingQuery.maybeSortBy,
              pagingQuery.order
            )
          }
        } yield response

      case GET -> Root / "id" / videoId as AuthenticatedRequestContext(user, requestId) =>
        apiSchedulingService
          .getById(videoId)
          .flatMap { scheduledVideoDownload =>
            Ok(scheduledVideoDownload)
          }

      case authRequest @ PUT -> Root / "id" / videoId as AuthenticatedRequestContext(user, requestId) =>
        for {
          UpdateScheduledVideoRequest(schedulingStatus) <- authRequest.to[UpdateScheduledVideoRequest]

          updatedScheduledVideoDownload <- apiSchedulingService.updateSchedulingStatus(videoId, schedulingStatus)

          response <- Ok(updatedScheduledVideoDownload)
        } yield response

      case GET -> Root / "active" as AuthenticatedRequestContext(user, requestId) =>
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

      case GET -> Root / "worker-status" as AuthenticatedRequestContext(user, requestId) =>
        apiSchedulingService.getWorkerStatus.flatMap {
          workerStatus => Ok(WorkerStatusResponse(workerStatus))
        }


      case authRequest @ PUT -> Root / "worker-status" as AuthenticatedRequestContext(user, requestId) =>
        for {
          workerStatusUpdateRequest <- authRequest.to[WorkerStatusUpdateRequest]

          _ <- apiSchedulingService.updateWorkerStatus(workerStatusUpdateRequest.workerStatus)

          response <- Ok(WorkerStatusResponse(workerStatusUpdateRequest.workerStatus))
        } yield response
    }
  }
}
