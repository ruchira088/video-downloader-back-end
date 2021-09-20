package com.ruchij.api.web.routes

import cats.effect.Sync
import cats.implicits._
import com.ruchij.api.daos.user.models.User
import com.ruchij.api.web.requests.{VideoMetadataRequest, VideoMetadataUpdateRequest}
import com.ruchij.api.web.requests.queryparams.SearchQuery
import com.ruchij.api.web.requests.RequestOps.AuthRequestOpsSyntax
import com.ruchij.api.web.requests.queryparams.SingleValueQueryParameter.DeleteVideoFileQueryParameter
import com.ruchij.core.services.video.{VideoAnalysisService, VideoService}
import com.ruchij.core.circe.Encoders._
import com.ruchij.api.web.responses.{IterableResponse, SearchResult}
import com.ruchij.core.services.models.SortBy
import com.ruchij.core.services.video.VideoAnalysisService.{Existing, NewlyCreated}
import io.circe.generic.auto._
import org.http4s.AuthedRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.circe.{decodeUri, encodeUri}
import org.http4s.dsl.Http4sDsl

object VideoRoutes {
  def apply[F[_]: Sync](videoService: VideoService[F], videoAnalysisService: VideoAnalysisService[F])(
    implicit dsl: Http4sDsl[F]
  ): AuthedRoutes[User, F] = {
    import dsl._

    AuthedRoutes.of[User, F] {
      case GET -> Root / "search" :? queryParameters as user =>
        for {
          SearchQuery(term, _, durationRange, sizeRange,  _, videoSites, pagingQuery) <-
            SearchQuery.fromQueryParameters[F].run(queryParameters)

          videos <- videoService.search(term, durationRange, sizeRange, pagingQuery.pageNumber, pagingQuery.pageSize, pagingQuery.maybeSortBy.getOrElse(SortBy.Date), pagingQuery.order, videoSites)

          response <- Ok(SearchResult(videos, pagingQuery.pageNumber, pagingQuery.pageSize, term, None, None, durationRange, sizeRange, pagingQuery.maybeSortBy, pagingQuery.order))
        } yield response

      case GET -> Root / "summary" as user =>
        videoService.summary.flatMap(videoServiceSummary => Ok(videoServiceSummary))

      case authRequest @ POST -> Root / "metadata" as user =>
        for {
          videoMetadataRequest <- authRequest.to[VideoMetadataRequest]

          result <- videoAnalysisService.metadata(videoMetadataRequest.url.withoutFragment)

          response <- result match {
            case Existing(videoMetadata) => Ok(videoMetadata)
            case NewlyCreated(videoMetadata) => Created(videoMetadata)
          }
        } yield response

      case GET -> Root / "id" / videoId as user => Ok(videoService.fetchById(videoId))

      case DELETE -> Root / "id" / videoId :? DeleteVideoFileQueryParameter(deleteVideoFile) as user =>
        Ok(videoService.deleteById(videoId, deleteVideoFile))

      case authRequest @ PATCH -> Root / "id" / videoId / "metadata" as user =>
        for {
          videoMetadataUpdateRequest <- authRequest.to[VideoMetadataUpdateRequest]

          updatedVideo <- videoService.update(videoId, videoMetadataUpdateRequest.title, None)

          response <- Ok(updatedVideo)
        }
        yield response

      case GET -> Root / "id" / videoId / "snapshots" as user =>
        videoService
          .fetchById(videoId)
          .productR(videoService.fetchVideoSnapshots(videoId))
          .flatMap(snapshots => Ok(IterableResponse(snapshots)))
    }
  }
}
