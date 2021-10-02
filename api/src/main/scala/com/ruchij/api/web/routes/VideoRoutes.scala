package com.ruchij.api.web.routes

import cats.effect.Sync
import cats.implicits._
import com.ruchij.api.services.models.Context.AuthenticatedRequestContext
import com.ruchij.api.web.requests.{VideoMetadataRequest, VideoMetadataUpdateRequest}
import com.ruchij.api.web.requests.queryparams.SearchQuery
import com.ruchij.api.web.requests.RequestOps.ContextRequestOpsSyntax
import com.ruchij.api.web.requests.queryparams.SingleValueQueryParameter.DeleteVideoFileQueryParameter
import com.ruchij.core.services.video.{VideoAnalysisService, VideoService}
import com.ruchij.core.circe.Encoders._
import com.ruchij.api.web.responses.{IterableResponse, SearchResult}
import com.ruchij.core.services.models.SortBy
import com.ruchij.core.services.video.VideoAnalysisService.{Existing, NewlyCreated}
import io.circe.generic.auto._
import org.http4s.ContextRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.circe.{decodeUri, encodeUri}
import org.http4s.dsl.Http4sDsl

object VideoRoutes {
  def apply[F[_]: Sync](videoService: VideoService[F], videoAnalysisService: VideoAnalysisService[F])(
    implicit dsl: Http4sDsl[F]
  ): ContextRoutes[AuthenticatedRequestContext, F] = {
    import dsl._

    ContextRoutes.of[AuthenticatedRequestContext, F] {
      case GET -> Root / "search" :? queryParameters as AuthenticatedRequestContext(user, requestId) =>
        for {
          SearchQuery(term, _, durationRange, sizeRange, urls, videoSites, pagingQuery) <- SearchQuery
            .fromQueryParameters[F]
            .run(queryParameters)

          videos <- videoService.search(
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

      case GET -> Root / "summary" as AuthenticatedRequestContext(user, requestId) =>
        videoService.summary.flatMap(videoServiceSummary => Ok(videoServiceSummary))

      case contextRequest @ POST -> Root / "metadata" as AuthenticatedRequestContext(user, requestId) =>
        for {
          videoMetadataRequest <- contextRequest.to[VideoMetadataRequest]

          result <- videoAnalysisService.metadata(videoMetadataRequest.url.withoutFragment)

          response <- result match {
            case Existing(videoMetadata) => Ok(videoMetadata)
            case NewlyCreated(videoMetadata) => Created(videoMetadata)
          }
        } yield response

      case GET -> Root / "id" / videoId as AuthenticatedRequestContext(user, requestId) =>
        Ok(videoService.fetchById(videoId, user.nonAdminUserId))

      case DELETE -> Root / "id" / videoId :? DeleteVideoFileQueryParameter(deleteVideoFile) as AuthenticatedRequestContext(user, requestId) =>
        Ok(videoService.deleteById(videoId, user.nonAdminUserId, deleteVideoFile))

      case contextRequest @ PATCH -> Root / "id" / videoId / "metadata" as AuthenticatedRequestContext(user, requestId) =>
        for {
          videoMetadataUpdateRequest <- contextRequest.to[VideoMetadataUpdateRequest]

          updatedVideo <- videoService.update(videoId, videoMetadataUpdateRequest.title, None, user.nonAdminUserId)

          response <- Ok(updatedVideo)
        } yield response

      case GET -> Root / "id" / videoId / "snapshots" as AuthenticatedRequestContext(user, requestId) =>
        videoService
          .fetchById(videoId, user.nonAdminUserId)
          .productR(videoService.fetchVideoSnapshots(videoId, user.nonAdminUserId))
          .flatMap(snapshots => Ok(IterableResponse(snapshots)))
    }
  }
}
