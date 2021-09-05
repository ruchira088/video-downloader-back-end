package com.ruchij.api.web.requests.queryparams

import cats.ApplicativeError
import cats.data.{Kleisli, NonEmptyList}
import com.ruchij.api.web.requests.queryparams.QueryParameter._
import com.ruchij.core.daos.scheduling.models.SchedulingStatus
import com.ruchij.core.daos.videometadata.models.VideoSite
import org.http4s.{QueryParamDecoder, Uri}

import scala.reflect.ClassTag

abstract class MultiValueQueryParameter[A: QueryParamDecoder: ClassTag](key: String)
    extends QueryParameter[Option[NonEmptyList[A]]] {
  override def parse[F[_]: ApplicativeError[*[_], Throwable]]: Kleisli[F, QueryParameters, Option[NonEmptyList[A]]] =
    QueryParameter
      .parse[F, Option[NonEmptyList[A]]](key)
      .map { values =>
        NonEmptyList.fromList {
          values.flatMap(_.map(_.toList).getOrElse(List.empty))
        }
      }
}

object MultiValueQueryParameter {
  case object VideoUrlsQueryParameter extends MultiValueQueryParameter[Uri](key = "video-url")

  case object SchedulingStatusesQueryParameter extends MultiValueQueryParameter[SchedulingStatus](key = "status")

  case object VideoSiteQueryParameter extends MultiValueQueryParameter[VideoSite](key = "site")
}
