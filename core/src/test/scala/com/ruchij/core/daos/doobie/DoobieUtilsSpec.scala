package com.ruchij.core.daos.doobie

import cats.effect.IO
import com.ruchij.core.daos.doobie.DoobieUtils.SingleUpdateOps
import com.ruchij.core.exceptions.InvalidConditionException
import com.ruchij.core.services.models.{Order, SortBy}
import com.ruchij.core.test.IOSupport.runIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class DoobieUtilsSpec extends AnyFlatSpec with Matchers {

  "SingleUpdateOps.one" should "succeed when exactly one row is affected" in runIO {
    val operation: IO[Int] = IO.pure(1)

    operation.one.map { _ =>
      succeed
    }
  }

  it should "raise InvalidConditionException when zero rows affected" in runIO {
    val operation: IO[Int] = IO.pure(0)

    operation.one.attempt.map {
      case Left(ex: InvalidConditionException) =>
        ex.getMessage must include("Expected a single row affected")
        ex.getMessage must include("0 rows affected")
      case other =>
        fail(s"Expected InvalidConditionException but got: $other")
    }
  }

  it should "raise InvalidConditionException when more than one row affected" in runIO {
    val operation: IO[Int] = IO.pure(5)

    operation.one.attempt.map {
      case Left(ex: InvalidConditionException) =>
        ex.getMessage must include("Expected a single row affected")
        ex.getMessage must include("5 rows affected")
      case other =>
        fail(s"Expected InvalidConditionException but got: $other")
    }
  }

  it should "raise InvalidConditionException for negative values" in runIO {
    val operation: IO[Int] = IO.pure(-1)

    operation.one.attempt.map {
      case Left(ex: InvalidConditionException) =>
        ex.getMessage must include("Expected a single row affected")
        ex.getMessage must include("-1 rows affected")
      case other =>
        fail(s"Expected InvalidConditionException but got: $other")
    }
  }

  "SingleUpdateOps.singleUpdate" should "return Some(()) when one row affected" in runIO {
    val operation: IO[Int] = IO.pure(1)

    operation.singleUpdate.value.map { result =>
      result mustBe Some(())
    }
  }

  it should "return None when zero rows affected" in runIO {
    val operation: IO[Int] = IO.pure(0)

    operation.singleUpdate.value.map { result =>
      result mustBe None
    }
  }

  it should "raise InvalidConditionException when multiple rows affected" in runIO {
    val operation: IO[Int] = IO.pure(3)

    operation.singleUpdate.value.attempt.map {
      case Left(ex: InvalidConditionException) =>
        ex.getMessage must include("0 or 1 row was expected")
        ex.getMessage must include("3 rows were updated")
      case other =>
        fail(s"Expected InvalidConditionException but got: $other")
    }
  }

  "sortByFieldName" should "map SortBy.Size to video_metadata.size" in {
    val fragment = DoobieUtils.sortByFieldName(SortBy.Size)
    fragment.internals.sql.trim mustBe "video_metadata.size"
  }

  it should "map SortBy.Duration to video_metadata.duration" in {
    val fragment = DoobieUtils.sortByFieldName(SortBy.Duration)
    fragment.internals.sql.trim mustBe "video_metadata.duration"
  }

  it should "map SortBy.Title to video_metadata.title" in {
    val fragment = DoobieUtils.sortByFieldName(SortBy.Title)
    fragment.internals.sql.trim mustBe "video_metadata.title"
  }

  it should "not be defined for SortBy.Date" in {
    DoobieUtils.sortByFieldName.isDefinedAt(SortBy.Date) mustBe false
  }

  "ordering" should "map Order.Ascending to ASC" in {
    val fragment = DoobieUtils.ordering(Order.Ascending)
    fragment.internals.sql.trim mustBe "ASC"
  }

  it should "map Order.Descending to DESC" in {
    val fragment = DoobieUtils.ordering(Order.Descending)
    fragment.internals.sql.trim mustBe "DESC"
  }
}
