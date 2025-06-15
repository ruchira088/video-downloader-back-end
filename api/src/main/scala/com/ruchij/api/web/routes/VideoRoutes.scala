package com.ruchij.api.web.routes

import cats.effect.Async
import cats.implicits._
import com.ruchij.api.daos.user.models.Role
import com.ruchij.api.daos.user.models.Role.Admin
import com.ruchij.api.services.models.Context.AuthenticatedRequestContext
import com.ruchij.api.services.video.ApiVideoService
import com.ruchij.api.web.middleware.Authorizer
import com.ruchij.api.web.requests.{VideoMetadataRequest, VideoMetadataUpdateRequest}
import com.ruchij.api.web.requests.queryparams.{PagingQuery, SearchQuery}
import com.ruchij.api.web.requests.RequestOps.ContextRequestOpsSyntax
import com.ruchij.api.web.requests.queryparams.SingleValueQueryParameter.DeleteVideoFileQueryParameter
import com.ruchij.core.services.video.{VideoAnalysisService, VideoWatchHistoryService}
import com.ruchij.core.circe.Encoders._
import com.ruchij.api.web.responses.{IterableResponse, SearchResult, VideoScanProgressResponse}
import com.ruchij.core.daos.workers.models.VideoScan
import com.ruchij.core.daos.workers.models.VideoScan.ScanStatus
import com.ruchij.core.daos.workers.models.VideoScan.ScanStatus.{Idle, InProgress}
import com.ruchij.core.services.models.SortBy
import com.ruchij.core.services.video.VideoAnalysisService.{Existing, NewlyCreated}
import io.circe.generic.auto._
import org.http4s.ContextRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl

object VideoRoutes {
  def apply[F[_]: Async](
    apiVideoService: ApiVideoService[F],
    videoAnalysisService: VideoAnalysisService[F],
    videoWatchHistoryService: VideoWatchHistoryService[F]
  )(implicit dsl: Http4sDsl[F]): ContextRoutes[AuthenticatedRequestContext, F] = {
    import dsl._

    ContextRoutes.of[AuthenticatedRequestContext, F] {
      case GET -> Root / "search" :? queryParameters as AuthenticatedRequestContext(user, _) =>
        for {
          SearchQuery(term, _, durationRange, sizeRange, urls, videoSites, pagingQuery) <- SearchQuery
            .fromQueryParameters[F]
            .run(queryParameters)

          videos <- apiVideoService.search(
            term,
            urls,
            durationRange,
            sizeRange,
            pagingQuery.pageNumber,
            pagingQuery.pageSize,
            pagingQuery.maybeSortBy.getOrElse(SortBy.Date),
            pagingQuery.order,
            videoSites,
            user.nonAdminUserId
          )

          response <- Ok(
            SearchResult(
              videos,
              pagingQuery.pageNumber,
              pagingQuery.pageSize,
              term,
              urls,
              None,
              durationRange,
              sizeRange,
              pagingQuery.maybeSortBy,
              pagingQuery.order
            )
          )
        } yield response

      case GET -> Root / "summary" as AuthenticatedRequestContext(user, _) if user.role == Admin =>
        apiVideoService.summary.flatMap(videoServiceSummary => Ok(videoServiceSummary))

      case GET -> Root / "history" :? queryParameters as AuthenticatedRequestContext(user, _) =>
        for {
          pagingQuery <- PagingQuery.from[F].run(queryParameters)
          videoWatchHistory <- videoWatchHistoryService.getWatchHistoryByUser(
            user.id,
            pagingQuery.pageSize,
            pagingQuery.pageNumber
          )
          response <- Ok(IterableResponse(videoWatchHistory))
        } yield response

      case GET -> Root / "scan" as AuthenticatedRequestContext(user, _) =>
        Authorizer(user.role == Admin) {
          apiVideoService.scanStatus
            .map {
              _.fold(VideoScanProgressResponse(None, Idle)) { videoScan =>
                VideoScanProgressResponse(Some(videoScan.updatedAt), videoScan.status)
              }
            }
            .flatMap(response => Ok(response))
        }

      case POST -> Root / "scan" as AuthenticatedRequestContext(user, _) =>
        Authorizer(user.role == Admin) {
          apiVideoService.scanForVideos
            .flatMap {
              case VideoScan(updatedAt, ScanStatus.InProgress) =>
                Ok(VideoScanProgressResponse(Some(updatedAt), InProgress))

              case videoScan => Created(VideoScanProgressResponse(Some(videoScan.updatedAt), videoScan.status))
            }
        }

      case contextRequest @ POST -> Root / "metadata" as _ =>
        for {
          videoMetadataRequest <- contextRequest.to[VideoMetadataRequest]

          result <- videoAnalysisService.metadata(videoMetadataRequest.url.withoutFragment)

          response <- result match {
            case Existing(videoMetadata) => Ok(videoMetadata)
            case NewlyCreated(videoMetadata) => Created(videoMetadata)
          }
        } yield response

      case GET -> Root / "id" / videoId as AuthenticatedRequestContext(user, _) =>
        Ok(apiVideoService.fetchById(videoId, user.nonAdminUserId))

      case DELETE -> Root / "id" / videoId :? DeleteVideoFileQueryParameter(deleteVideoFile) as AuthenticatedRequestContext(
            user,
            _
          ) =>
        Ok(apiVideoService.deleteById(videoId, user.nonAdminUserId, deleteVideoFile))

      case contextRequest @ PATCH -> Root / "id" / videoId / "metadata" as AuthenticatedRequestContext(user, _) =>
        for {
          videoMetadataUpdateRequest <- contextRequest.to[VideoMetadataUpdateRequest]

          updatedVideo <- apiVideoService.update(videoId, videoMetadataUpdateRequest.title, user.nonAdminUserId)

          response <- Ok(updatedVideo)
        } yield response

      case GET -> Root / "id" / videoId / "snapshots" as AuthenticatedRequestContext(user, _) =>
        apiVideoService
          .fetchById(videoId, user.nonAdminUserId)
          .productR(apiVideoService.fetchVideoSnapshots(videoId, user.nonAdminUserId))
          .flatMap(snapshots => Ok(IterableResponse(snapshots)))

      case POST -> Root / "queue-incomplete-downloads" as AuthenticatedRequestContext(user, _)
          if user.role == Role.Admin =>
        apiVideoService.queueIncorrectlyCompletedVideos.flatMap(videos => Ok(IterableResponse(videos)))
    }
  }
}
