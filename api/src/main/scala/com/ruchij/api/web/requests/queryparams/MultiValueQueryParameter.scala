package com.ruchij.api.web.requests.queryparams

import cats.ApplicativeError
import cats.data.{Kleisli, NonEmptyList}
import com.ruchij.api.web.requests.queryparams.QueryParameter.QueryParameters
import org.http4s.{QueryParamDecoder, Uri}

abstract class MultiValueQueryParameter[A: QueryParamDecoder](key: String) extends QueryParameter[Option[NonEmptyList[A]]] {
  override def parse[F[_]: ApplicativeError[*[_], Throwable]]: Kleisli[F, QueryParameters, Option[NonEmptyList[A]]] =
    QueryParameter.parse[F, A](key)
      .map {
        case head :: tail => Some(NonEmptyList(head, tail))
        case Nil => None
      }
}

object MultiValueQueryParameter {
  case object VideoUrlsQueryParameter extends MultiValueQueryParameter[Uri](key = "video-url")
}
