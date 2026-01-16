package com.ruchij.api.services.authentication

import cats.effect.IO
import com.ruchij.api.daos.user.models.Email
import com.ruchij.api.exceptions.AuthenticationException
import com.ruchij.api.services.authentication.AuthenticationService.{Password, Secret}
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class NoAuthenticationServiceSpec extends AnyFlatSpec with Matchers {

  private val service = new NoAuthenticationService[IO]

  "login" should "always return AuthenticationDisabled error" in runIO {
    service.login(Email("test@example.com"), Password("password")).error.map { error =>
      error mustBe AuthenticationException.AuthenticationDisabled
    }
  }

  "authenticate" should "always return AuthenticationDisabled error" in runIO {
    service.authenticate(Secret("some-secret-token")).error.map { error =>
      error mustBe AuthenticationException.AuthenticationDisabled
    }
  }

  "logout" should "always return AuthenticationDisabled error" in runIO {
    service.logout(Secret("some-secret-token")).error.map { error =>
      error mustBe AuthenticationException.AuthenticationDisabled
    }
  }

  "AuthenticationService.secretGenerator" should "generate a Secret from UUID" in runIO {
    val secretGen = AuthenticationService.secretGenerator[IO]

    secretGen.generate.flatMap { secret =>
      IO.delay {
        secret.value.length mustBe 36 // UUID format
        secret.value must include("-")
      }
    }
  }

  it should "generate unique secrets on each call" in runIO {
    val secretGen = AuthenticationService.secretGenerator[IO]

    for {
      secret1 <- secretGen.generate
      secret2 <- secretGen.generate
      _ <- IO.delay {
        secret1.value must not equal secret2.value
      }
    } yield ()
  }

  "Password and Secret case classes" should "wrap values correctly" in {
    val password = Password("my-secret-password")
    val secret = Secret("secret-token-value")

    password.value mustBe "my-secret-password"
    secret.value mustBe "secret-token-value"
  }
}
