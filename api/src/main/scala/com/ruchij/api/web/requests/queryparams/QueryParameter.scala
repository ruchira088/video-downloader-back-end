package com.ruchij.api.web.requests.queryparams

import java.util.concurrent.TimeUnit
import cats.data.Validated.{Invalid, Valid}
import cats.data.{Kleisli, NonEmptyList, Validated, ValidatedNel}
import cats.implicits._
import cats.{Applicative, ApplicativeError}
import com.ruchij.api.web.requests.queryparams.QueryParameter.QueryParameters
import com.ruchij.core.daos.scheduling.models.RangeValue
import com.ruchij.core.daos.videometadata.models.VideoSite
import com.ruchij.core.exceptions.AggregatedException
import enumeratum.{Enum, EnumEntry}
import org.http4s.{ParseFailure, QueryParamDecoder, QueryParameterValue}

import scala.concurrent.duration.FiniteDuration
import scala.reflect.{ClassTag, classTag}

trait QueryParameter[A] {
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

  implicit def rangeValueQueryParamDecoder[A: QueryParamDecoder: Ordering]: QueryParamDecoder[RangeValue[A]] =
    (queryParameterValue: QueryParameterValue) => {
      val valuesList = queryParameterValue.value.split('-').toList

      if(valuesList.size > 2) Validated.Invalid(NonEmptyList.one(ParseFailure(queryParameterValue.value, "Range contains more than 2 terms")))
      else
        valuesList
          .traverse { stringValue =>
            if (stringValue.isEmpty) Validated.Valid[Option[A]](None)
            else QueryParamDecoder[A].decode(QueryParameterValue(stringValue)).map(Some.apply)
          }
          .andThen {
            case min :: max :: Nil =>
              RangeValue
                .create(min, max)
                .left
                .map(validationException => ParseFailure(queryParameterValue.value, validationException.message))
                .toValidatedNel

            case min :: Nil =>
              RangeValue(min, None).validNel

            case _ => RangeValue.all[A].validNel
          }
    }

  implicit val finiteDurationQueryParamDecoder: QueryParamDecoder[FiniteDuration] =
    QueryParamDecoder[Long].map(number => FiniteDuration(number, TimeUnit.MINUTES))

  implicit val videoSiteQueryParamDecoder: QueryParamDecoder[VideoSite] =
    QueryParamDecoder[String].map(VideoSite.from)

  implicit def optionQueryParamDecoder[A: QueryParamDecoder]: QueryParamDecoder[Option[A]] =
    (queryParameterValue: QueryParameterValue) =>
      if (queryParameterValue.value.trim.isEmpty) Valid(None)
      else QueryParamDecoder[A].decode(queryParameterValue).map(Some.apply)

  implicit def nonEmptyListQueryParamDecoder[A: QueryParamDecoder: ClassTag]: QueryParamDecoder[NonEmptyList[A]] =
    (queryParameterValue: QueryParameterValue) =>
      queryParameterValue.value
        .split(",")
        .map(_.trim)
        .filter(_.nonEmpty)
        .toList
        .traverse(value => QueryParamDecoder[A].decode(QueryParameterValue(value)))
        .fold[ValidatedNel[ParseFailure, NonEmptyList[A]]](
          Invalid.apply, {
            case head :: tail => Valid(NonEmptyList(head, tail))

            case Nil =>
              Invalid {
                NonEmptyList.one {
                  ParseFailure(
                    "Values cannot be empty",
                    s"""Unable to parse "${queryParameterValue.value}" as non-empty list of ${classTag[A].runtimeClass.getSimpleName}"""
                  )
                }
              }
          }
      )

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
