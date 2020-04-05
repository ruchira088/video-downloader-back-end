package com.ruchij.web.requests.queryparams

import cats.{Applicative, ApplicativeError}
import com.ruchij.exceptions.AggregatedException
import org.http4s.{ParseFailure, QueryParamDecoder, QueryParameterValue}

abstract class QueryParameter[A: QueryParamDecoder](key: String, defaultValue: A) {
  def parse[F[_]: ApplicativeError[*[_], Throwable]](param: Map[String, collection.Seq[String]]): F[A] =
    param
      .get(key)
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

object QueryParameter {
  implicit def queryParamDecoder[A: QueryParamDecoder]: QueryParamDecoder[Option[A]] =
    (value: QueryParameterValue) => QueryParamDecoder[A].decode(value).map(Some.apply)

  case object PageNumberQueryParameter extends QueryParameter(key = "page-number", defaultValue = 0)

  case object PageSizeQueryParameter extends QueryParameter(key = "page-size", defaultValue = 10)

  case object SearchTermQueryParameter extends QueryParameter[Option[String]](key = "search-term", defaultValue = None)
}

