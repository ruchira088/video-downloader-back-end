package com.ruchij.api.web.requests.queryparams

import cats.data.{Kleisli, NonEmptyList}
import cats.effect.IO
import com.ruchij.api.daos.user.models.Role
import com.ruchij.api.web.requests.queryparams.QueryParameter._
import com.ruchij.core.daos.scheduling.models.RangeValue
import com.ruchij.core.daos.videometadata.models.VideoSite
import com.ruchij.core.exceptions.AggregatedException
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import org.http4s.{QueryParamDecoder, QueryParameterValue}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

class QueryParameterSpec extends AnyFlatSpec with Matchers {

  "QueryParameter.parse" should "parse a single query parameter value" in runIO {
    val params: QueryParameters = Map("count" -> Seq("10"))

    QueryParameter.parse[IO, Int]("count").run(params).map { result =>
      result mustBe List(10)
    }
  }

  it should "parse multiple query parameter values" in runIO {
    val params: QueryParameters = Map("ids" -> Seq("1", "2", "3"))

    QueryParameter.parse[IO, Int]("ids").run(params).map { result =>
      result mustBe List(1, 2, 3)
    }
  }

  it should "return empty list for missing query parameter" in runIO {
    val params: QueryParameters = Map.empty

    QueryParameter.parse[IO, Int]("missing").run(params).map { result =>
      result mustBe List.empty
    }
  }

  it should "raise AggregatedException for invalid query parameter" in runIO {
    val params: QueryParameters = Map("count" -> Seq("not-a-number"))

    QueryParameter.parse[IO, Int]("count").run(params).error.map { error =>
      error mustBe an[AggregatedException[_]]
      val aggregated = error.asInstanceOf[AggregatedException[Exception]]
      aggregated.errors.head.getMessage must include("count")
    }
  }

  it should "parse string query parameters" in runIO {
    val params: QueryParameters = Map("name" -> Seq("hello", "world"))

    QueryParameter.parse[IO, String]("name").run(params).map { result =>
      result mustBe List("hello", "world")
    }
  }

  "rangeValueQueryParamDecoder" should "parse full range with min and max" in {
    val result = QueryParamDecoder[RangeValue[Int]].decode(QueryParameterValue("5-10"))

    result.isValid mustBe true
    result.toOption.get mustBe RangeValue(Some(5), Some(10))
  }

  it should "parse range with only min value" in {
    val result = QueryParamDecoder[RangeValue[Int]].decode(QueryParameterValue("5-"))

    result.isValid mustBe true
    result.toOption.get mustBe RangeValue(Some(5), None)
  }

  it should "parse range with only max value" in {
    val result = QueryParamDecoder[RangeValue[Int]].decode(QueryParameterValue("-10"))

    result.isValid mustBe true
    result.toOption.get mustBe RangeValue(None, Some(10))
  }

  it should "parse empty range as all" in {
    val result = QueryParamDecoder[RangeValue[Int]].decode(QueryParameterValue("-"))

    result.isValid mustBe true
    result.toOption.get mustBe RangeValue(None, None)
  }

  it should "parse single value as min only" in {
    val result = QueryParamDecoder[RangeValue[Int]].decode(QueryParameterValue("5"))

    result.isValid mustBe true
    result.toOption.get mustBe RangeValue(Some(5), None)
  }

  it should "reject range with more than 2 terms" in {
    val result = QueryParamDecoder[RangeValue[Int]].decode(QueryParameterValue("1-5-10"))

    result.isInvalid mustBe true
    result.toEither.left.toOption.get.head.details must include("more than 2 terms")
  }

  it should "reject range where min is greater than max" in {
    val result = QueryParamDecoder[RangeValue[Int]].decode(QueryParameterValue("10-5"))

    result.isInvalid mustBe true
    result.toEither.left.toOption.get.head.details must include("greater than maximum")
  }

  it should "parse range with Long values" in {
    val result = QueryParamDecoder[RangeValue[Long]].decode(QueryParameterValue("1000000-2000000"))

    result.isValid mustBe true
    result.toOption.get mustBe RangeValue(Some(1000000L), Some(2000000L))
  }

  it should "reject range with invalid number format" in {
    val result = QueryParamDecoder[RangeValue[Int]].decode(QueryParameterValue("abc-10"))

    result.isInvalid mustBe true
  }

  "finiteDurationQueryParamDecoder" should "parse Long to FiniteDuration in minutes" in {
    val result = QueryParamDecoder[FiniteDuration].decode(QueryParameterValue("30"))

    result.isValid mustBe true
    result.toOption.get mustBe FiniteDuration(30, TimeUnit.MINUTES)
  }

  it should "parse zero minutes" in {
    val result = QueryParamDecoder[FiniteDuration].decode(QueryParameterValue("0"))

    result.isValid mustBe true
    result.toOption.get mustBe FiniteDuration(0, TimeUnit.MINUTES)
  }

  it should "parse large duration values" in {
    val result = QueryParamDecoder[FiniteDuration].decode(QueryParameterValue("1440"))

    result.isValid mustBe true
    result.toOption.get mustBe FiniteDuration(1440, TimeUnit.MINUTES)
  }

  "videoSiteQueryParamDecoder" should "parse known video site" in {
    val result = QueryParamDecoder[VideoSite].decode(QueryParameterValue("youtube"))

    result.isValid mustBe true
    result.toOption.get.name mustBe "youtube"
  }

  it should "parse Local video site" in {
    val result = QueryParamDecoder[VideoSite].decode(QueryParameterValue("Local"))

    result.isValid mustBe true
    result.toOption.get mustBe VideoSite.Local
  }

  it should "parse custom video site" in {
    val result = QueryParamDecoder[VideoSite].decode(QueryParameterValue("SpankBang"))

    result.isValid mustBe true
    result.toOption.get.name mustBe "SpankBang"
  }

  "optionQueryParamDecoder" should "parse non-empty value as Some" in {
    val result = QueryParamDecoder[Option[Int]].decode(QueryParameterValue("42"))

    result.isValid mustBe true
    result.toOption.get mustBe Some(42)
  }

  it should "parse empty value as None" in {
    val result = QueryParamDecoder[Option[Int]].decode(QueryParameterValue(""))

    result.isValid mustBe true
    result.toOption.get mustBe None
  }

  it should "parse whitespace-only value as None" in {
    val result = QueryParamDecoder[Option[Int]].decode(QueryParameterValue("   "))

    result.isValid mustBe true
    result.toOption.get mustBe None
  }

  it should "parse optional string value" in {
    val result = QueryParamDecoder[Option[String]].decode(QueryParameterValue("hello"))

    result.isValid mustBe true
    result.toOption.get mustBe Some("hello")
  }

  it should "return Invalid for unparseable non-empty value" in {
    val result = QueryParamDecoder[Option[Int]].decode(QueryParameterValue("not-a-number"))

    result.isInvalid mustBe true
  }

  "nonEmptyListQueryParamDecoder" should "parse comma-separated values" in {
    val result = QueryParamDecoder[NonEmptyList[Int]].decode(QueryParameterValue("1,2,3"))

    result.isValid mustBe true
    result.toOption.get mustBe NonEmptyList.of(1, 2, 3)
  }

  it should "parse single value" in {
    val result = QueryParamDecoder[NonEmptyList[Int]].decode(QueryParameterValue("42"))

    result.isValid mustBe true
    result.toOption.get mustBe NonEmptyList.of(42)
  }

  it should "trim whitespace from values" in {
    val result = QueryParamDecoder[NonEmptyList[Int]].decode(QueryParameterValue("1 , 2 , 3"))

    result.isValid mustBe true
    result.toOption.get mustBe NonEmptyList.of(1, 2, 3)
  }

  it should "filter out empty values between commas" in {
    val result = QueryParamDecoder[NonEmptyList[Int]].decode(QueryParameterValue("1,,2,3"))

    result.isValid mustBe true
    result.toOption.get mustBe NonEmptyList.of(1, 2, 3)
  }

  it should "reject empty string" in {
    val result = QueryParamDecoder[NonEmptyList[Int]].decode(QueryParameterValue(""))

    result.isInvalid mustBe true
    result.toEither.left.toOption.get.head.sanitized must include("cannot be empty")
  }

  it should "reject string with only commas and whitespace" in {
    val result = QueryParamDecoder[NonEmptyList[Int]].decode(QueryParameterValue(", , ,"))

    result.isInvalid mustBe true
    result.toEither.left.toOption.get.head.details must include("non-empty list")
  }

  it should "parse non-empty list of strings" in {
    val result = QueryParamDecoder[NonEmptyList[String]].decode(QueryParameterValue("a,b,c"))

    result.isValid mustBe true
    result.toOption.get mustBe NonEmptyList.of("a", "b", "c")
  }

  it should "reject list with invalid values" in {
    val result = QueryParamDecoder[NonEmptyList[Int]].decode(QueryParameterValue("1,abc,3"))

    result.isInvalid mustBe true
  }

  "enumQueryParamDecoder" should "parse valid enum value" in {
    val result = QueryParamDecoder[Role].decode(QueryParameterValue("User"))

    result.isValid mustBe true
    result.toOption.get mustBe Role.User
  }

  it should "parse enum value case-insensitively" in {
    val result = QueryParamDecoder[Role].decode(QueryParameterValue("admin"))

    result.isValid mustBe true
    result.toOption.get mustBe Role.Admin
  }

  it should "parse enum value with mixed case" in {
    val result = QueryParamDecoder[Role].decode(QueryParameterValue("ADMIN"))

    result.isValid mustBe true
    result.toOption.get mustBe Role.Admin
  }

  it should "reject invalid enum value" in {
    val result = QueryParamDecoder[Role].decode(QueryParameterValue("InvalidRole"))

    result.isInvalid mustBe true
    val error = result.toEither.left.toOption.get.head
    error.sanitized must include("InvalidRole")
    error.sanitized must include("Possible values")
    error.sanitized must include("User")
    error.sanitized must include("Admin")
  }

  "QueryParameter trait" should "implement unapply correctly for successful parse" in {
    val queryParam = new QueryParameter[Int] {
      override def parse[F[_]: cats.ApplicativeError[*[_], Throwable]]: Kleisli[F, QueryParameters, Int] =
        Kleisli { params =>
          params.get("value").flatMap(_.headOption).map(_.toInt) match {
            case Some(v) => cats.Applicative[F].pure(v)
            case None => cats.ApplicativeError[F, Throwable].raiseError(new RuntimeException("Missing"))
          }
        }
    }

    val params: QueryParameters = Map("value" -> Seq("42"))
    val result = queryParam.unapply(params)

    result mustBe Some(42)
  }

  it should "return None for failed parse in unapply" in {
    val queryParam = new QueryParameter[Int] {
      override def parse[F[_]: cats.ApplicativeError[*[_], Throwable]]: Kleisli[F, QueryParameters, Int] =
        Kleisli { _ =>
          cats.ApplicativeError[F, Throwable].raiseError(new RuntimeException("Always fails"))
        }
    }

    val params: QueryParameters = Map("value" -> Seq("42"))
    val result = queryParam.unapply(params)

    result mustBe None
  }

  "rangeValueQueryParamDecoder with FiniteDuration" should "parse duration range" in {
    val result = QueryParamDecoder[RangeValue[FiniteDuration]].decode(QueryParameterValue("10-60"))

    result.isValid mustBe true
    val rangeValue = result.toOption.get
    rangeValue.min mustBe Some(FiniteDuration(10, TimeUnit.MINUTES))
    rangeValue.max mustBe Some(FiniteDuration(60, TimeUnit.MINUTES))
  }

  "QueryParameter.parse" should "handle empty parameter value in list" in runIO {
    val params: QueryParameters = Map("items" -> Seq())

    QueryParameter.parse[IO, String]("items").run(params).map { result =>
      result mustBe List.empty
    }
  }
}
