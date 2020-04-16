package com.ruchij.web.routes

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Sync
import cats.implicits._
import com.ruchij.circe.Encoders._
import com.ruchij.services.video.VideoService
import com.ruchij.types.FunctionKTypes.eitherToF
import com.ruchij.web.requests.queryparams.QueryParameter.{PageNumberQueryParameter, PageSizeQueryParameter, SearchTermQueryParameter}
import com.ruchij.web.responses.{SearchResult, VideoFileResponse}
import com.ruchij.web.responses.VideoFileResponse.VideoFileResponseOps
import io.circe.generic.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.circe.encodeUri
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Range

object VideoRoutes {
  def apply[F[_]: Sync](videoService: VideoService[F])(implicit dsl: Http4sDsl[F]): HttpRoutes[F] = {
    import dsl._

    HttpRoutes.of {
      case GET -> Root / "search" :? queryParameters =>
        for {
          pageNumber <- PageNumberQueryParameter.parse[F](queryParameters)
          pageSize <- PageSizeQueryParameter.parse[F](queryParameters)
          searchTerm <- SearchTermQueryParameter.parse[F](queryParameters)

          items <- videoService.search(searchTerm, pageNumber, pageSize)
          response <- Ok(SearchResult(items, pageNumber, pageSize, searchTerm))
        }
        yield response

      case GET -> Root / "key" / videoKey => Ok(videoService.fetchByKey(videoKey))

      case request @ GET -> Root / "file" / videoKey =>
        for {
          range <- Applicative[F].pure {
            request.headers.get(Range).map { case Range(_, NonEmptyList(subRange, _)) => subRange }
          }

          (video, videoStream) <-
            videoService.fetchResourceByVideoKey(videoKey, range.map(_.first), range.flatMap(_.second))

          response <- VideoFileResponse(video, videoStream, range).asResponse
        }
        yield response
    }
  }
}
