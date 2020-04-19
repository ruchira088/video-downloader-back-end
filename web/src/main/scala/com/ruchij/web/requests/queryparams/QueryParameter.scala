package com.ruchij.web.requests.queryparams

import cats.data.Kleisli
import cats.{Applicative, ApplicativeError, MonadError}
import com.ruchij.exceptions.AggregatedException
import com.ruchij.web.requests.queryparams.QueryParameter.QueryParameters
import org.http4s.{ParseFailure, QueryParamDecoder, QueryParameterValue}

abstract class QueryParameter[A: QueryParamDecoder](key: String, defaultValue: A) {
  def parse[F[_]: ApplicativeError[*[_], Throwable]]: Kleisli[F, QueryParameters, A] =
    Kleisli[F, QueryParameters, A] {
        _.get(key)
          .flatMap(_.headOption)
          .fold[F[A]](Applicative[F].pure(defaultValue)) { rawValue =>
            QueryParamDecoder[A]
              .decode(QueryParameterValue(rawValue))
              .fold(
                errors => ApplicativeError[F, Throwable].raiseError(AggregatedException[ParseFailure](errors)),
                parsedValue => Applicative[F].pure(parsedValue)
              )
          }
    }

}

object QueryParameter {
  type QueryParameters = Map[String, collection.Seq[String]]

  implicit def queryParamDecoder[A: QueryParamDecoder]: QueryParamDecoder[Option[A]] =
    (value: QueryParameterValue) => QueryParamDecoder[A].decode(value).map(Some.apply)

  case class SearchQuery(term: Option[String], pageSize: Int, pageNumber: Int)

  object SearchQuery {
    def fromQueryParameters[F[_]: MonadError[*[_], Throwable]]: Kleisli[F, QueryParameters, SearchQuery] =
      for {
        searchTerm <- SearchTermQueryParameter.parse[F]
        pageSize <- PageSizeQueryParameter.parse[F]
        pageNumber <- PageNumberQueryParameter.parse[F]
      }
      yield SearchQuery(searchTerm, pageSize, pageNumber)
  }

  case object PageNumberQueryParameter extends QueryParameter(key = "page-number", defaultValue = 0)

  case object PageSizeQueryParameter extends QueryParameter(key = "page-size", defaultValue = 10)

  case object SearchTermQueryParameter extends QueryParameter[Option[String]](key = "search-term", defaultValue = None)
}

