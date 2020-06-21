package com.ruchij.web.requests.queryparams

import cats.data.{Kleisli, NonEmptyList, ValidatedNel}
import cats.data.Validated.{Invalid, Valid}
import cats.{Applicative, ApplicativeError, MonadError}
import com.ruchij.exceptions.AggregatedException
import com.ruchij.services.models.{Order, SortBy}
import com.ruchij.web.requests.queryparams.QueryParameter.QueryParameters
import enumeratum.{Enum, EnumEntry}
import org.http4s.{ParseFailure, QueryParamDecoder, QueryParameterValue}

import scala.reflect.ClassTag

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

  implicit def enumQueryParamDecoder[A <: EnumEntry](
    implicit enumValue: Enum[A],
    classTag: ClassTag[A]
  ): QueryParamDecoder[A] =
    (queryParameterValue: QueryParameterValue) =>
      enumValue.values
        .find(_.entryName.equalsIgnoreCase(queryParameterValue.value))
        .fold[ValidatedNel[ParseFailure, A]](
          Invalid(
            NonEmptyList.of(
              ParseFailure(
                s"Possible values are [${enumValue.values.map(_.entryName).mkString(", ")}]. Unable to parse value as ${classTag.runtimeClass.getSimpleName}",
                queryParameterValue.value
              )
            )
          )
        )(Valid.apply)

  case class SearchQuery(term: Option[String], pageSize: Int, pageNumber: Int, sortBy: SortBy, order: Order)

  object SearchQuery {
    def fromQueryParameters[F[_]: MonadError[*[_], Throwable]]: Kleisli[F, QueryParameters, SearchQuery] =
      for {
        searchTerm <- SearchTermQueryParameter.parse[F]
        pageSize <- PageSizeQueryParameter.parse[F]
        pageNumber <- PageNumberQueryParameter.parse[F]
        sortBy <- SortByQueryParameter.parse[F]
        order <- OrderQueryParameter.parse[F]
      } yield SearchQuery(searchTerm, pageSize, pageNumber, sortBy, order)
  }

  case object PageNumberQueryParameter extends QueryParameter(key = "page-number", defaultValue = 0)

  case object PageSizeQueryParameter extends QueryParameter(key = "page-size", defaultValue = 10)

  case object SearchTermQueryParameter extends QueryParameter[Option[String]](key = "search-term", defaultValue = None)

  case object SortByQueryParameter extends QueryParameter[SortBy]("sort-by", defaultValue = SortBy.Date)

  case object OrderQueryParameter extends QueryParameter[Order]("order", defaultValue = Order.Descending)
}
