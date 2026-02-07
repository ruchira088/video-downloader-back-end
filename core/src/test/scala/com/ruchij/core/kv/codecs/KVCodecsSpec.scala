package com.ruchij.core.kv.codecs

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.ruchij.core.daos.scheduling.models.SchedulingStatus
import com.ruchij.core.services.models.Order
import com.ruchij.core.test.IOSupport.runIO
import java.time.{Instant, ZoneOffset}
import com.ruchij.core.types.TimeUtils
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import shapeless.{::, HNil}

class KVCodecsSpec extends AnyFlatSpec with Matchers {

  "KVEncoder[String]" should "encode strings as-is" in runIO {
    val encoder = KVEncoder[IO, String]
    for {
      result <- encoder.encode("hello world")
    } yield {
      result mustBe "hello world"
    }
  }

  it should "handle empty strings" in runIO {
    val encoder = KVEncoder[IO, String]
    for {
      result <- encoder.encode("")
    } yield {
      result mustBe ""
    }
  }

  "KVDecoder[String]" should "decode strings as-is" in runIO {
    val decoder = KVDecoder[IO, String]
    for {
      result <- decoder.decode("hello world")
    } yield {
      result mustBe "hello world"
    }
  }

  "KVEncoder[Long]" should "encode Long values" in runIO {
    val encoder = KVEncoder[IO, Long]
    for {
      result <- encoder.encode(12345L)
    } yield {
      result mustBe "12345"
    }
  }

  it should "encode negative Long values" in runIO {
    val encoder = KVEncoder[IO, Long]
    for {
      result <- encoder.encode(-9876L)
    } yield {
      result mustBe "-9876"
    }
  }

  "KVDecoder[Long]" should "decode Long values" in runIO {
    val decoder = KVDecoder[IO, Long]
    for {
      result <- decoder.decode("12345")
    } yield {
      result mustBe 12345L
    }
  }

  it should "fail for non-numeric strings" in runIO {
    val decoder = KVDecoder[IO, Long]
    for {
      result <- decoder.decode("not-a-number").attempt
    } yield {
      result.isLeft mustBe true
      result.left.exists(_.isInstanceOf[IllegalArgumentException]) mustBe true
    }
  }

  "KVDecoder[Int]" should "decode Int values" in runIO {
    val decoder = KVDecoder[IO, Int]
    for {
      result <- decoder.decode("42")
    } yield {
      result mustBe 42
    }
  }

  it should "decode negative Int values" in runIO {
    val decoder = KVDecoder[IO, Int]
    for {
      result <- decoder.decode("-100")
    } yield {
      result mustBe -100
    }
  }

  "KVEncoder[Instant]" should "encode Instant values" in runIO {
    val encoder = KVEncoder[IO, Instant]
    val dateTime = TimeUtils.instantOf(2023, 5, 15, 10, 30)
    for {
      result <- encoder.encode(dateTime)
    } yield {
      result must include("2023-05-15")
    }
  }

  "KVDecoder[Instant]" should "decode Instant values" in runIO {
    val decoder = KVDecoder[IO, Instant]
    for {
      result <- decoder.decode("2023-05-15T10:30:00Z")
    } yield {
      val zdt = result.atZone(ZoneOffset.UTC)
      zdt.getYear mustBe 2023
      zdt.getMonthValue mustBe 5
      zdt.getDayOfMonth mustBe 15
    }
  }

  it should "fail for invalid date strings" in runIO {
    val decoder = KVDecoder[IO, Instant]
    for {
      result <- decoder.decode("not-a-date").attempt
    } yield {
      result.isLeft mustBe true
    }
  }

  "KVEncoder for enum" should "encode SchedulingStatus" in runIO {
    val encoder = KVEncoder[IO, SchedulingStatus]
    for {
      result <- encoder.encode(SchedulingStatus.Queued)
    } yield {
      result mustBe "Queued"
    }
  }

  it should "encode Order enum" in runIO {
    val encoder = KVEncoder[IO, Order]
    for {
      result <- encoder.encode(Order.Ascending)
    } yield {
      result mustBe "asc"
    }
  }

  "KVDecoder for enum" should "decode SchedulingStatus" in runIO {
    val decoder = KVDecoder[IO, SchedulingStatus]
    for {
      result <- decoder.decode("Completed")
    } yield {
      result mustBe SchedulingStatus.Completed
    }
  }

  it should "decode case-insensitively" in runIO {
    val decoder = KVDecoder[IO, SchedulingStatus]
    for {
      result <- decoder.decode("queued")
    } yield {
      result mustBe SchedulingStatus.Queued
    }
  }

  it should "fail for invalid enum value" in runIO {
    val decoder = KVDecoder[IO, SchedulingStatus]
    for {
      result <- decoder.decode("InvalidStatus").attempt
    } yield {
      result.isLeft mustBe true
    }
  }

  "KVEncoder.coMap" should "transform values before encoding" in runIO {
    val intEncoder = KVEncoder[IO, String].coMap[Int](_.toString)
    for {
      result <- intEncoder.encode(42)
    } yield {
      result mustBe "42"
    }
  }

  "KVDecoder.map" should "transform decoded values" in runIO {
    val intDecoder = KVDecoder[IO, String].map(_.length)
    for {
      result <- intDecoder.decode("hello")
    } yield {
      result mustBe 5
    }
  }

  "KVDecoder.mapF" should "transform decoded values with effect" in runIO {
    val intDecoder = KVDecoder[IO, String].mapF(s => IO.pure(s.length * 2))
    for {
      result <- intDecoder.decode("hello")
    } yield {
      result mustBe 10
    }
  }

  "KVDecoder.mapEither" should "transform with validation" in runIO {
    val positiveDecoder = KVDecoder[IO, Int].mapEither { n =>
      if (n > 0) Right(n) else Left("Must be positive")
    }
    for {
      result <- positiveDecoder.decode("42")
    } yield {
      result mustBe 42
    }
  }

  it should "fail validation" in runIO {
    val positiveDecoder = KVDecoder[IO, Int].mapEither { n =>
      if (n > 0) Right(n) else Left("Must be positive")
    }
    for {
      result <- positiveDecoder.decode("-5").attempt
    } yield {
      result.isLeft mustBe true
      result.left.exists(_.getMessage == "Must be positive") mustBe true
    }
  }

  "KVCodec" should "create codec from encoder and decoder" in runIO {
    val codec = KVCodec[IO, String]
    for {
      encoded <- codec.encode("test")
      decoded <- codec.decode(encoded)
    } yield {
      decoded mustBe "test"
    }
  }

  "KVEncoder for HList" should "encode single element HList" in runIO {
    val encoder = implicitly[KVEncoder[IO, String :: HNil]]
    for {
      result <- encoder.encode("hello" :: HNil)
    } yield {
      result mustBe "hello"
    }
  }

  it should "encode multi-element HList with separator" in runIO {
    val encoder = implicitly[KVEncoder[IO, String :: String :: HNil]]
    for {
      result <- encoder.encode("hello" :: "world" :: HNil)
    } yield {
      result mustBe "hello::world"  // KeySeparator is "::"
    }
  }

  "KVDecoder for HList" should "decode single element HList" in runIO {
    val decoder = implicitly[KVDecoder[IO, String :: HNil]]
    for {
      result <- decoder.decode("hello")
    } yield {
      result mustBe ("hello" :: HNil)
    }
  }

  it should "decode multi-element HList" in runIO {
    val decoder = implicitly[KVDecoder[IO, String :: String :: HNil]]
    for {
      result <- decoder.decode("hello::world")  // KeySeparator is "::"
    } yield {
      result mustBe ("hello" :: "world" :: HNil)
    }
  }

  it should "fail for empty value when expecting HNil" in runIO {
    val decoder = implicitly[KVDecoder[IO, HNil]]
    for {
      result <- decoder.decode("extra-terms").attempt
    } yield {
      result.isLeft mustBe true
      result.left.exists(_.getMessage.contains("extra terms")) mustBe true
    }
  }

  it should "succeed for empty string when expecting HNil" in runIO {
    val decoder = implicitly[KVDecoder[IO, HNil]]
    for {
      result <- decoder.decode("")
    } yield {
      result mustBe HNil
    }
  }

  "KVEncoder for HNil" should "fail to encode" in runIO {
    val encoder = implicitly[KVEncoder[IO, HNil]]
    for {
      result <- encoder.encode(HNil).attempt
    } yield {
      result.isLeft mustBe true
    }
  }

  "Instant roundtrip" should "preserve value" in runIO {
    val now = Instant.now()
    val encoder = KVEncoder[IO, Instant]
    val decoder = KVDecoder[IO, Instant]
    for {
      encoded <- encoder.encode(now)
      decoded <- decoder.decode(encoded)
    } yield {
      decoded.toEpochMilli mustBe now.toEpochMilli
    }
  }

  "Long roundtrip" should "preserve value" in runIO {
    val value = 9876543210L
    val encoder = KVEncoder[IO, Long]
    val decoder = KVDecoder[IO, Long]
    for {
      encoded <- encoder.encode(value)
      decoded <- decoder.decode(encoded)
    } yield {
      decoded mustBe value
    }
  }

  "SchedulingStatus roundtrip" should "preserve value" in runIO {
    SchedulingStatus.values.foreach { status =>
      val encoder = KVEncoder[IO, SchedulingStatus]
      val decoder = KVDecoder[IO, SchedulingStatus]
      val test = for {
        encoded <- encoder.encode(status)
        decoded <- decoder.decode(encoded)
      } yield {
        decoded mustBe status
      }
      test.unsafeRunSync()
    }
    IO.unit
  }

  "KVEncoder.coMapF" should "transform values with effect before encoding" in runIO {
    val baseEncoder = KVEncoder[IO, String]
    val lengthEncoder: KVEncoder[IO, String] = baseEncoder.coMapF[String, String](s => IO.pure(s.toUpperCase))
    for {
      result <- lengthEncoder.encode("hello")
    } yield {
      result mustBe "HELLO"
    }
  }

  it should "handle failed effects" in runIO {
    val baseEncoder = KVEncoder[IO, String]
    val failingEncoder: KVEncoder[IO, String] = baseEncoder.coMapF[String, String](_ =>
      IO.raiseError(new RuntimeException("encoding failed"))
    )
    for {
      result <- failingEncoder.encode("test").attempt
    } yield {
      result.isLeft mustBe true
      result.left.exists(_.getMessage == "encoding failed") mustBe true
    }
  }

  it should "chain effects correctly" in runIO {
    val baseEncoder = KVEncoder[IO, Long]
    val stringToLongEncoder: KVEncoder[IO, String] = baseEncoder.coMapF[String, Long](s =>
      IO.delay(s.length.toLong)
    )
    for {
      result <- stringToLongEncoder.encode("hello world")
    } yield {
      result mustBe "11"
    }
  }

  "KVDecoder.TermCount" should "count single non-product term as 1" in {
    val stringTermCount = KVDecoder.TermCount[String]
    stringTermCount.size mustBe 1
  }

  it should "count HNil as 0" in {
    val hnilTermCount = KVDecoder.TermCount[HNil]
    hnilTermCount.size mustBe 0
  }

  it should "count HList terms correctly" in {
    val hlistTermCount = KVDecoder.TermCount[String :: String :: HNil]
    hlistTermCount.size mustBe 2
  }

  it should "count three-element HList correctly" in {
    val hlistTermCount = KVDecoder.TermCount[String :: Long :: String :: HNil]
    hlistTermCount.size mustBe 3
  }
}
