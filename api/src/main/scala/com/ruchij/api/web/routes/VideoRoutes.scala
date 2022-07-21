package com.ruchij.api.web.routes

import cats.effect.Async
import cats.implicits._
import com.ruchij.api.services.models.Context.AuthenticatedRequestContext
import com.ruchij.api.services.video.ApiVideoService
import com.ruchij.api.services.video.models.VideoScanProgress
import com.ruchij.api.web.requests.{VideoMetadataRequest, VideoMetadataUpdateRequest}
import com.ruchij.api.web.requests.queryparams.SearchQuery
import com.ruchij.api.web.requests.RequestOps.ContextRequestOpsSyntax
import com.ruchij.api.web.requests.queryparams.SingleValueQueryParameter.DeleteVideoFileQueryParameter
import com.ruchij.core.services.video.VideoAnalysisService
import com.ruchij.core.circe.Decoders._
import com.ruchij.core.circe.Encoders._
import com.ruchij.api.web.responses.{IterableResponse, SearchResult, VideoScanProgressResponse}
import com.ruchij.core.services.models.SortBy
import com.ruchij.core.services.video.VideoAnalysisService.{Existing, NewlyCreated}
import io.circe.generic.auto._
import org.http4s.ContextRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl

object VideoRoutes {
  def apply[F[_]: Async](apiVideoService: ApiVideoService[F], videoAnalysisService: VideoAnalysisService[F])(
    implicit dsl: Http4sDsl[F]
  ): ContextRoutes[AuthenticatedRequestContext, F] = {
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

      case GET -> Root / "summary" as _ =>
        apiVideoService.summary.flatMap(videoServiceSummary => Ok(videoServiceSummary))

      case POST -> Root / "scan" as _ =>
        apiVideoService.scanForVideos
          .flatMap {
            case VideoScanProgress.ScanInProgress(startedAt) => Ok(VideoScanProgressResponse(startedAt))

            case VideoScanProgress.ScanStarted(startedAt) => Created(VideoScanProgressResponse(startedAt))
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

      case DELETE -> Root / "id" / videoId :? DeleteVideoFileQueryParameter(deleteVideoFile) as AuthenticatedRequestContext(user, _) =>
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
    }
  }
}
