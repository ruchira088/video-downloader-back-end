package com.ruchij.core.services.hashing

import cats.effect.IO
import com.ruchij.core.test.IOSupport.runIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class MurmurHash3ServiceSpec extends AnyFlatSpec with Matchers {

  private val hashingService = new MurmurHash3Service[IO]

  "hash" should "produce a consistent hex hash for the same input" in runIO {
    for {
      hash1 <- hashingService.hash("test-string")
      hash2 <- hashingService.hash("test-string")
    } yield {
      hash1 mustBe hash2
    }
  }

  it should "produce different hashes for different inputs" in runIO {
    for {
      hash1 <- hashingService.hash("input-1")
      hash2 <- hashingService.hash("input-2")
    } yield {
      hash1 must not be hash2
    }
  }

  it should "produce a hex string output" in runIO {
    hashingService.hash("any-value").map { hash =>
      hash.matches("[0-9a-f]+") mustBe true
    }
  }

  it should "handle empty strings" in runIO {
    hashingService.hash("").map { hash =>
      hash.nonEmpty mustBe true
    }
  }

  it should "handle unicode characters" in runIO {
    hashingService.hash("日本語テスト").map { hash =>
      hash.matches("[0-9a-f]+") mustBe true
    }
  }

  it should "handle long strings" in runIO {
    val longString = "a" * 10000
    hashingService.hash(longString).map { hash =>
      hash.nonEmpty mustBe true
    }
  }
}
