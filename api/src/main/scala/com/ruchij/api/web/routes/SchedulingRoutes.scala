package com.ruchij.api.web.routes

import cats.effect.Async
import cats.implicits._
import com.ruchij.api.services.models.Context.AuthenticatedRequestContext
import com.ruchij.api.services.scheduling.ApiSchedulingService
import com.ruchij.api.web.requests.RequestOps.ContextRequestOpsSyntax
import com.ruchij.api.web.requests.UpdateScheduledVideoRequest.updateScheduledVideoRequestValidator
import com.ruchij.api.web.requests.queryparams.SearchQuery
import com.ruchij.api.web.requests.{SchedulingRequest, UpdateScheduledVideoRequest, WorkerStatusUpdateRequest}
import com.ruchij.api.web.responses.EventStreamEventType.{ActiveDownload, HeartBeat, ScheduledVideoDownloadUpdate}
import com.ruchij.api.web.responses.{EventStreamHeartBeat, IterableResponse, SearchResult, WorkerStatusResponse}
import com.ruchij.core.circe.Decoders._
import com.ruchij.core.circe.Encoders._
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.services.models.SortBy
import com.ruchij.core.services.scheduling.models.DownloadProgress
import com.ruchij.core.types.JodaClock
import fs2.Stream
import io.circe.Encoder
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.{ContextRoutes, ServerSentEvent}

import scala.concurrent.duration._
import scala.language.postfixOps

object SchedulingRoutes {
  def apply[F[_]: Async: JodaClock](
    apiSchedulingService: ApiSchedulingService[F],
    downloadProgressStream: Stream[F, DownloadProgress],
    scheduledVideoDownloadUpdatesStream: Stream[F, ScheduledVideoDownload]
  )(implicit dsl: Http4sDsl[F]): ContextRoutes[AuthenticatedRequestContext, F] = {
    import dsl._

    ContextRoutes.of[AuthenticatedRequestContext, F] {
      case contextRequest @ POST -> Root as AuthenticatedRequestContext(user, _) =>
        for {
          scheduleRequest <- contextRequest.to[SchedulingRequest]
          scheduledVideoResult <- apiSchedulingService.schedule(scheduleRequest.url.withoutFragment, user.id)

          response <- if (scheduledVideoResult.isNew) Created(scheduledVideoResult.scheduledVideoDownload)
          else Ok(scheduledVideoResult.scheduledVideoDownload)
        } yield response

      case POST -> Root / "retry-failed" as AuthenticatedRequestContext(user, _) =>
        for {
          scheduledVideoDownloads <- apiSchedulingService.retryFailed(user.nonAdminUserId)
          response <- Ok { IterableResponse(scheduledVideoDownloads) }
        } yield response

      case GET -> Root / "search" :? queryParameters as AuthenticatedRequestContext(user, _) =>
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

      case GET -> Root / "id" / videoId as AuthenticatedRequestContext(user, _) =>
        apiSchedulingService
          .getById(videoId, user.nonAdminUserId)
          .flatMap { scheduledVideoDownload =>
            Ok(scheduledVideoDownload)
          }

      case DELETE -> Root / "id" / videoId as AuthenticatedRequestContext(user, _) =>
        apiSchedulingService
          .deleteById(videoId, user.nonAdminUserId)
          .flatMap { scheduledVideoDownload =>
            Ok(scheduledVideoDownload)
          }

      case authRequest @ PUT -> Root / "id" / videoId as _ =>
        for {
          UpdateScheduledVideoRequest(schedulingStatus) <- authRequest.to[UpdateScheduledVideoRequest]

          updatedScheduledVideoDownload <- apiSchedulingService.updateSchedulingStatus(videoId, schedulingStatus)

          response <- Ok(updatedScheduledVideoDownload)
        } yield response

      case GET -> Root / "updates" as _ =>
        Ok {
          downloadProgressStream
            .map { downloadProgress =>
              ServerSentEvent(Some(Encoder[DownloadProgress].apply(downloadProgress).noSpaces), ActiveDownload)
            }
            .merge {
              scheduledVideoDownloadUpdatesStream.map { scheduledVideoDownload =>
                ServerSentEvent(
                  Some(Encoder[ScheduledVideoDownload].apply(scheduledVideoDownload).noSpaces),
                  ScheduledVideoDownloadUpdate
                )
              }
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

      case GET -> Root / "worker-status" as _ =>
        apiSchedulingService.getWorkerStatus.flatMap { workerStatus =>
          Ok(WorkerStatusResponse(workerStatus))
        }

      case authRequest @ PUT -> Root / "worker-status" as _ =>
        for {
          workerStatusUpdateRequest <- authRequest.to[WorkerStatusUpdateRequest]

          _ <- apiSchedulingService.updateWorkerStatus(workerStatusUpdateRequest.workerStatus)

          response <- Ok(WorkerStatusResponse(workerStatusUpdateRequest.workerStatus))
        } yield response
    }
  }
}
