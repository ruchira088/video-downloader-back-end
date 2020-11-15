package com.ruchij.api.web.requests.queryparams

import java.util.concurrent.TimeUnit

import cats.data.Validated.{Invalid, Valid}
import cats.data.{Kleisli, NonEmptyList, ValidatedNel}
import cats.implicits._
import cats.{Applicative, ApplicativeError}
import com.ruchij.api.web.requests.queryparams.QueryParameter.QueryParameters
import com.ruchij.core.exceptions.AggregatedException
import com.ruchij.core.services.video.models.DurationRange
import enumeratum.{Enum, EnumEntry}
import org.http4s.{ParseFailure, QueryParamDecoder, QueryParameterValue}

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

abstract class QueryParameter[A] {
  def parse[F[_]: ApplicativeError[*[_], Throwable]]: Kleisli[F, QueryParameters, A]

  def unapply(params: QueryParameters): Option[A] =
    parse[Either[Throwable, *]].run(params).toOption
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
              errors =>
                ApplicativeError[F, Throwable].raiseError(
                  AggregatedException(
                    errors.map(
                      parseFailure =>
                        new IllegalArgumentException(s"Query parameter parse error: $key - ${parseFailure.sanitized}")
                    )
                  )
              ),
              parsedValue => Applicative[F].pure(parsedValue)
            )
        }
    }

  implicit val durationRangeQueryParamDecoder: QueryParamDecoder[DurationRange] = {
    case QueryParameterValue(inputValue) =>
      inputValue
        .split("-")
        .toList
        .take(2)
        .traverse { value =>
          if (value.trim.isEmpty) None.validNel[ParseFailure]
          else
            value.trim.toLongOption.fold[ValidatedNel[ParseFailure, Some[FiniteDuration]]](
              ParseFailure(s"""Unable to parse "$value" as a Long""", "").invalidNel[Some[FiniteDuration]]
            )(number => Some(FiniteDuration(number, TimeUnit.MINUTES)).validNel[ParseFailure])
        }
        .andThen {
          case min :: max :: Nil =>
            DurationRange
              .create(min, max)
              .left
              .map(throwable => ParseFailure(inputValue, throwable.getMessage))
              .toValidatedNel
          case min :: Nil => DurationRange(min, None).validNel
          case _ => DurationRange.All.validNel
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
                s"""Unable to parse "${queryParameterValue.value}" as ${classTag.runtimeClass.getSimpleName}. Possible values are [${enumValue.values
                  .map(_.entryName)
                  .mkString(", ")}]""",
                ""
              )
            )
          )
        )(Valid.apply)
}
