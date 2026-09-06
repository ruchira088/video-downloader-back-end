package com.ruchij.core.types

import cats.data.Kleisli
import cats.effect.IO
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.FunctionKTypes._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class FunctionKTypesSpec extends AnyFlatSpec with Matchers {

  "FunctionKTypes.eitherToF" should "convert Right to F[A]" in runIO {
    val either: Either[Throwable, Int] = Right(42)
    for {
      result <- eitherToF[Throwable, IO].apply(either)
    } yield {
      result mustBe 42
    }
  }

  it should "convert Left to error in F" in runIO {
    val error = new RuntimeException("test error")
    val either: Either[Throwable, Int] = Left(error)
    for {
      result <- eitherToF[Throwable, IO].apply(either).attempt
    } yield {
      result.isLeft mustBe true
      result.left.toOption.get mustBe error
    }
  }

  "FunctionKTypes.eitherLeftFunctor" should "map left values" in {
    val either: Either[Int, String] = Left(5)
    val mapped = eitherLeftFunctor[String].map(either)(_ * 2)
    mapped mustBe Left(10)
  }

  it should "leave right values unchanged" in {
    val either: Either[Int, String] = Right("hello")
    val mapped = eitherLeftFunctor[String].map(either)(_ * 2)
    mapped mustBe Right("hello")
  }

  "FunctionKTypes.identityFunctionK" should "return identity transformation" in runIO {
    val io = IO.pure(42)
    for {
      result <- identityFunctionK[IO].apply(io)
    } yield {
      result mustBe 42
    }
  }

  "FunctionKTypes.optionToOptionT" should "convert Some to OptionT" in runIO {
    val option: Option[Int] = Some(42)
    for {
      result <- optionToOptionT[IO].apply(option).value
    } yield {
      result mustBe Some(42)
    }
  }

  it should "convert None to OptionT" in runIO {
    val option: Option[Int] = None
    for {
      result <- optionToOptionT[IO].apply(option).value
    } yield {
      result mustBe None
    }
  }

  "FunctionKTypes.FunctionKTypeOps.toType" should "unwrap OptionT with value" in runIO {
    val option: Option[Int] = Some(42)
    for {
      result <- option.toType[IO, Throwable](new RuntimeException("empty"))
    } yield {
      result mustBe 42
    }
  }

  it should "raise error for None" in runIO {
    val option: Option[Int] = None
    for {
      result <- option.toType[IO, Throwable](new RuntimeException("empty")).attempt
    } yield {
      result.isLeft mustBe true
      result.left.toOption.get.getMessage mustBe "empty"
    }
  }

  "FunctionKTypes.KleisliOption.or" should "use first Kleisli when Some" in runIO {
    val kleisli1: Kleisli[IO, Int, Option[String]] = Kleisli(_ => IO.pure(Some("first")))
    val kleisli2: Kleisli[IO, Int, String] = Kleisli(_ => IO.pure("second"))

    for {
      result <- kleisli1.or(kleisli2).run(42)
    } yield {
      result mustBe "first"
    }
  }

  it should "use fallback Kleisli when None" in runIO {
    val kleisli1: Kleisli[IO, Int, Option[String]] = Kleisli(_ => IO.pure(Option.empty[String]))
    val kleisli2: Kleisli[IO, Int, String] = Kleisli(_ => IO.pure("fallback"))

    for {
      result <- kleisli1.or(kleisli2).run(42)
    } yield {
      result mustBe "fallback"
    }
  }
}
