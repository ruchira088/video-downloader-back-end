package com.ruchij.core.types

import cats.data.{Kleisli, NonEmptyList}
import cats.effect.IO
import cats.implicits._
import com.ruchij.core.exceptions.ValidationException
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.FunctionKTypes._
import java.time.Instant
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.UUID

class TypesSpec extends AnyFlatSpec with Matchers {

  "Clock[IO].timestamp" should "return current datetime" in runIO {
    for {
      before <- IO.delay(Instant.now())
      timestamp <- Clock[IO].timestamp
      after <- IO.delay(Instant.now())
    } yield {
      timestamp.isAfter(before.minusSeconds(1)) mustBe true
      timestamp.isBefore(after.plusSeconds(1)) mustBe true
    }
  }

  "Clock.apply" should "return the implicit instance" in runIO {
    val clock = Clock[IO]
    for {
      timestamp <- clock.timestamp
    } yield {
      timestamp mustBe a[Instant]
    }
  }

  "RandomGenerator[IO, UUID].generate" should "produce valid UUIDs" in runIO {
    for {
      uuid <- RandomGenerator[IO, UUID].generate
    } yield {
      uuid mustBe a[UUID]
      uuid.toString.length mustBe 36
    }
  }

  it should "produce unique UUIDs" in runIO {
    for {
      uuid1 <- RandomGenerator[IO, UUID].generate
      uuid2 <- RandomGenerator[IO, UUID].generate
    } yield {
      uuid1 mustNot be(uuid2)
    }
  }

  "RandomGenerator.range" should "produce values in range" in runIO {
    val generator = RandomGenerator.range[IO](0, 10)
    for {
      values <- (1 to 100).toList.traverse(_ => generator.generate)
    } yield {
      values.foreach { value =>
        value must be >= 0
        value must be < 10
      }
    }
  }

  it should "fail when start > end" in runIO {
    val generator = RandomGenerator.range[IO](10, 5)
    for {
      result <- generator.generate.attempt
    } yield {
      result.isLeft mustBe true
      result.left.exists(_.isInstanceOf[ValidationException]) mustBe true
    }
  }

  "RandomGenerator.from" should "produce values from the list" in runIO {
    val values = NonEmptyList.of("apple", "banana", "cherry")
    val generator = RandomGenerator.from[IO, String](values)
    for {
      results <- (1 to 100).toList.traverse(_ => generator.generate)
    } yield {
      results.foreach { result =>
        values.toList must contain(result)
      }
    }
  }

  "RandomGenerator.eval" should "wrap an effect" in runIO {
    val generator = RandomGenerator.eval[IO, Int](IO.pure(42))
    for {
      result <- generator.generate
    } yield {
      result mustBe 42
    }
  }

  "RandomGenerator[IO].apply with block" should "wrap a delayed computation" in runIO {
    var counter = 0
    val generator = RandomGenerator[IO, Int] {
      counter += 1
      counter
    }
    for {
      result1 <- generator.generate
      result2 <- generator.generate
    } yield {
      result1 mustBe 1
      result2 mustBe 2
    }
  }

  "RandomGenerator.evalMap" should "transform the generated value" in runIO {
    val generator = RandomGenerator.range[IO](1, 100).evalMap(n => IO.pure(n * 2))
    for {
      result <- generator.generate
    } yield {
      result must be >= 2
      result must be < 200
      result % 2 mustBe 0
    }
  }

  "RandomGenerator monad" should "support pure" in runIO {
    import cats.syntax.applicative._

    val generator = 42.pure[RandomGenerator[IO, *]]
    for {
      result <- generator.generate
    } yield {
      result mustBe 42
    }
  }

  it should "support flatMap" in runIO {
    import cats.Monad
    val monad = Monad[RandomGenerator[IO, *]]

    val generator = monad.flatMap(RandomGenerator.range[IO](1, 10)) { n =>
      monad.pure(n * 10)
    }
    for {
      result <- generator.generate
    } yield {
      result must be >= 10
      result must be < 100
    }
  }

  "dateTimeRandomGenerator" should "produce Instant values relative to now" in runIO {
    for {
      now <- Clock[IO].timestamp
      randomDateTime <- RandomGenerator[IO, Instant].generate
    } yield {
      // The dateTimeRandomGenerator uses offset range(-10_000, 0).map(now.minusMinutes(_))
      // minusMinutes(negative) = plusMinutes(positive), so value is in the future
      // The random datetime should be between now and 10,000 minutes in the future
      randomDateTime.isAfter(now.minus(java.time.Duration.ofMinutes(1))) mustBe true
      randomDateTime.isBefore(now.plus(java.time.Duration.ofMinutes(10001))) mustBe true
    }
  }

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
