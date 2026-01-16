package com.ruchij.api.services.hashing

import cats.effect.IO
import com.ruchij.api.services.authentication.AuthenticationService.Password
import com.ruchij.core.test.IOSupport.runIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class BCryptPasswordHashingServiceSpec extends AnyFlatSpec with Matchers {

  private val service = new BCryptPasswordHashingService[IO]

  "hashPassword" should "hash a password" in runIO {
    val password = Password("mySecretPassword123")

    service.hashPassword(password).map { hashedPassword =>
      hashedPassword.value must not be password.value
      hashedPassword.value must startWith("$2a$")
      hashedPassword.value.length must be > 50
    }
  }

  it should "produce different hashes for the same password" in runIO {
    val password = Password("samePassword")

    for {
      hash1 <- service.hashPassword(password)
      hash2 <- service.hashPassword(password)
    } yield {
      hash1.value must not equal hash2.value
    }
  }

  it should "hash an empty password" in runIO {
    val password = Password("")

    service.hashPassword(password).map { hashedPassword =>
      hashedPassword.value must startWith("$2a$")
    }
  }

  it should "hash a very long password" in runIO {
    val password = Password("a" * 1000)

    service.hashPassword(password).map { hashedPassword =>
      hashedPassword.value must startWith("$2a$")
    }
  }

  it should "hash a password with special characters" in runIO {
    val password = Password("p@$$w0rd!#%^&*()_+{}|:<>?")

    service.hashPassword(password).map { hashedPassword =>
      hashedPassword.value must startWith("$2a$")
    }
  }

  it should "hash a password with unicode characters" in runIO {
    val password = Password("密码パスワード")

    service.hashPassword(password).map { hashedPassword =>
      hashedPassword.value must startWith("$2a$")
    }
  }

  "checkPassword" should "return true for matching password" in runIO {
    val password = Password("correctPassword")

    for {
      hashedPassword <- service.hashPassword(password)
      result <- service.checkPassword(password, hashedPassword)
    } yield {
      result mustBe true
    }
  }

  it should "return false for non-matching password" in runIO {
    val password = Password("correctPassword")
    val wrongPassword = Password("wrongPassword")

    for {
      hashedPassword <- service.hashPassword(password)
      result <- service.checkPassword(wrongPassword, hashedPassword)
    } yield {
      result mustBe false
    }
  }

  it should "return false for similar but different password" in runIO {
    val password = Password("password123")
    val similarPassword = Password("password124")

    for {
      hashedPassword <- service.hashPassword(password)
      result <- service.checkPassword(similarPassword, hashedPassword)
    } yield {
      result mustBe false
    }
  }

  it should "handle case sensitivity correctly" in runIO {
    val password = Password("Password123")
    val lowercasePassword = Password("password123")

    for {
      hashedPassword <- service.hashPassword(password)
      result <- service.checkPassword(lowercasePassword, hashedPassword)
    } yield {
      result mustBe false
    }
  }

  it should "return true for empty password when hashed correctly" in runIO {
    val password = Password("")

    for {
      hashedPassword <- service.hashPassword(password)
      result <- service.checkPassword(password, hashedPassword)
    } yield {
      result mustBe true
    }
  }

  it should "return false for empty password against non-empty hash" in runIO {
    val password = Password("nonEmpty")
    val emptyPassword = Password("")

    for {
      hashedPassword <- service.hashPassword(password)
      result <- service.checkPassword(emptyPassword, hashedPassword)
    } yield {
      result mustBe false
    }
  }

  it should "verify against a valid BCrypt hash format" in runIO {
    val password = Password("correctPassword")

    for {
      hashedPassword <- service.hashPassword(password)
      // Verify the hash follows BCrypt format
      _ <- IO.delay {
        hashedPassword.value must startWith("$2a$")
        hashedPassword.value.length mustBe 60
      }
      result <- service.checkPassword(password, hashedPassword)
    } yield {
      result mustBe true
    }
  }

  it should "handle password with newlines" in runIO {
    val password = Password("password\nwith\nnewlines")

    for {
      hashedPassword <- service.hashPassword(password)
      result <- service.checkPassword(password, hashedPassword)
    } yield {
      result mustBe true
    }
  }

  it should "handle password with tabs and spaces" in runIO {
    val password = Password("password\twith\t spaces")

    for {
      hashedPassword <- service.hashPassword(password)
      result <- service.checkPassword(password, hashedPassword)
    } yield {
      result mustBe true
    }
  }
}
