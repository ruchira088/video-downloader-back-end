package com.ruchij.web.requests.queryparams

import cats.data.Validated.{Invalid, Valid}
import cats.data.{Kleisli, NonEmptyList, ValidatedNel}
import cats.implicits._
import cats.{Applicative, ApplicativeError}
import com.ruchij.exceptions.AggregatedException
import com.ruchij.web.requests.queryparams.QueryParameter.QueryParameters
import enumeratum.{Enum, EnumEntry}
import org.http4s.{ParseFailure, QueryParamDecoder, QueryParameterValue}

import scala.reflect.ClassTag

abstract class QueryParameter[A] {
  def parse[F[_]: ApplicativeError[*[_], Throwable]]: Kleisli[F, QueryParameters, A]
}

object QueryParameter {
  type QueryParameters = Map[String, collection.Seq[String]]

  def parse[F[_]: ApplicativeError[*[_], Throwable], A: QueryParamDecoder](
    key: String
  ): Kleisli[F, QueryParameters, List[A]] =
    Kleisli[F, QueryParameters, List[A]] {
      _.get(key).toList.flatten
        .traverse { rawValue =>
          QueryParamDecoder[A]
            .decode(QueryParameterValue(rawValue))
            .fold(
              errors => ApplicativeError[F, Throwable].raiseError(AggregatedException[ParseFailure](errors)),
              parsedValue => Applicative[F].pure(parsedValue)
            )
        }
    }

  implicit def optionQueryParamDecoder[A: QueryParamDecoder]: QueryParamDecoder[Option[A]] =
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
}
