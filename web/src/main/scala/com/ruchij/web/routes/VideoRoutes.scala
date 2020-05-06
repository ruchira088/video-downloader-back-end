package com.ruchij.web.routes

import cats.effect.Sync
import cats.implicits._
import com.ruchij.circe.Encoders._
import com.ruchij.services.video.VideoService
import com.ruchij.web.requests.queryparams.QueryParameter.SearchQuery
import com.ruchij.web.responses.SearchResult
import io.circe.generic.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.circe.encodeUri
import org.http4s.dsl.Http4sDsl

object VideoRoutes {
  def apply[F[_]: Sync](videoService: VideoService[F])(implicit dsl: Http4sDsl[F]): HttpRoutes[F] = {
    import dsl._

    HttpRoutes.of {
      case GET -> Root / "search" :? queryParameters =>
        for {
          SearchQuery(term, pageSize, pageNumber) <- SearchQuery.fromQueryParameters[F].run(queryParameters)

          videos <- videoService.search(term, pageNumber, pageSize)

          response <- Ok(SearchResult(videos, pageNumber, pageSize, term))
        }
        yield response

      case GET -> Root / "id" / videoKey => Ok(videoService.fetchById(videoKey))
    }
  }
}
