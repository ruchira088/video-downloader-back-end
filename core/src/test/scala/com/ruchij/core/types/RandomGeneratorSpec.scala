package com.ruchij.core.types

import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits._
import com.ruchij.core.test.IOSupport.runIO
import java.time.Instant
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.UUID

class RandomGeneratorSpec extends AnyFlatSpec with Matchers {

  "RandomGenerator[F, UUID]" should "generate random UUIDs" in runIO {
    for {
      uuid1 <- RandomGenerator[IO, UUID].generate
      uuid2 <- RandomGenerator[IO, UUID].generate
    } yield {
      uuid1 must not be uuid2
      uuid1 mustBe a[UUID]
      uuid2 mustBe a[UUID]
    }
  }

  "RandomGenerator[F, Instant]" should "generate random Instant" in runIO {
    for {
      dateTime <- RandomGenerator[IO, Instant].generate
    } yield {
      dateTime mustBe a[Instant]
    }
  }

  "RandomGenerator.from" should "randomly select from non-empty list" in runIO {
    val items = NonEmptyList.of("apple", "banana", "cherry")
    val generator = RandomGenerator.from[IO, String](items)

    for {
      result1 <- generator.generate
      result2 <- generator.generate
      result3 <- generator.generate
    } yield {
      items.toList must contain(result1)
      items.toList must contain(result2)
      items.toList must contain(result3)
    }
  }

  "RandomGenerator.range" should "generate numbers within specified range" in runIO {
    val min = 10
    val max = 20
    val generator = RandomGenerator.range[IO](min, max)

    for {
      results <- (1 to 100).toList.traverse(_ => generator.generate)
    } yield {
      results.foreach { result =>
        result must be >= min
        result must be < max
      }
    }
  }

  it should "handle single value range" in runIO {
    val generator = RandomGenerator.range[IO](5, 6)

    generator.generate.map { result =>
      result mustBe 5
    }
  }

  "RandomGenerator.eval" should "wrap an effect in a generator" in runIO {
    val effect = IO.pure(42)
    val generator = RandomGenerator.eval(effect)

    generator.generate.map { result =>
      result mustBe 42
    }
  }

  "RandomGenerator monad" should "support flatMap operations" in runIO {
    val prefixes = NonEmptyList.of("prefix-a", "prefix-b")
    val suffixes = NonEmptyList.of("-suffix-1", "-suffix-2")

    import RandomGenerator.randomGeneratorMonad

    val combinedGenerator = for {
      prefix <- RandomGenerator.from[IO, String](prefixes)
      suffix <- RandomGenerator.from[IO, String](suffixes)
    } yield prefix + suffix

    combinedGenerator.generate.map { result =>
      result must (startWith("prefix-") and include("-suffix-"))
    }
  }

  "RandomGenerator evalMap" should "transform generated values with an effect" in runIO {
    import RandomGenerator.RandomGeneratorOps

    val generator = RandomGenerator[IO, UUID].evalMap(uuid => IO.pure(uuid.toString))

    generator.generate.map { result =>
      result mustBe a[String]
      result.length mustBe 36 // UUID string length
    }
  }

  "RandomGenerator.range" should "fail when start is greater than end" in runIO {
    val generator = RandomGenerator.range[IO](20, 10)

    generator.generate.attempt.map { result =>
      result.isLeft mustBe true
      result.left.exists(_.getMessage.contains("must be less than")) mustBe true
    }
  }

  "RandomGenerator monad pure" should "wrap a value in a generator" in runIO {
    import RandomGenerator.randomGeneratorMonad
    import cats.Monad

    val generator = Monad[RandomGenerator[IO, *]].pure("test value")

    generator.generate.map { result =>
      result mustBe "test value"
    }
  }

  "RandomGenerator monad tailRecM" should "support tail recursive operations" in runIO {
    import RandomGenerator.randomGeneratorMonad
    import cats.Monad

    val monad = Monad[RandomGenerator[IO, *]]

    // Count up to 10 using tailRecM
    val generator: RandomGenerator[IO, Int] = monad.tailRecM[Int, Int](0) { n =>
      if (n >= 10) monad.pure[Either[Int, Int]](Right(n))
      else monad.pure[Either[Int, Int]](Left(n + 1))
    }

    generator.generate.map { result =>
      result mustBe 10
    }
  }

  "RandomGenerator apply with block" should "lazily evaluate the block" in runIO {
    var counter = 0
    val generator = RandomGenerator[IO, Int] {
      counter += 1
      counter
    }

    for {
      _ <- IO(counter mustBe 0) // Not evaluated yet
      r1 <- generator.generate
      r2 <- generator.generate
    } yield {
      r1 mustBe 1
      r2 mustBe 2
      counter mustBe 2
    }
  }
}
