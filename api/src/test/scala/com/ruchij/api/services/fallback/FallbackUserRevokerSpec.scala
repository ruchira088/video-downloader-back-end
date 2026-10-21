package com.ruchij.api.services.fallback

import cats.effect.IO
import com.ruchij.api.daos.user.models.{Email, Role, User}
import com.ruchij.api.services.fallback.FallbackSyncStubs.{FlakyTransport, RecordingTransport}
import com.ruchij.api.services.fallback.aws.FallbackSyncTransport
import com.ruchij.api.services.fallback.models.{MainToFallbackMessage, UserRevocation}
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.Providers
import com.ruchij.core.types.{Clock, TimeUtils}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class FallbackUserRevokerSpec extends AnyFlatSpec with Matchers {
  private val timestamp = TimeUtils.instantOf(2026, 9, 26, 8, 25)
  private implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

  private val user =
    User("user-1", TimeUtils.instantOf(2024, 5, 15, 10, 30), "John", "Doe", Email("me@ruchij.com"), Role.User)

  "TransportFallbackUserRevoker" should "send a UserRevocation for the user, captured now" in runIO {
    for {
      transport <- RecordingTransport()
      _ <- new TransportFallbackUserRevoker[IO](transport).revoke(user)
      messages <- transport.messages
    } yield messages mustBe List(UserRevocation("user-1", "me@ruchij.com", timestamp))
  }

  it should "not fail the caller when the revocation can't be sent" in runIO {
    for {
      transport <- FlakyTransport(failures = 1)
      _ <- new TransportFallbackUserRevoker[IO](transport).revoke(user)
      messages <- transport.recording.messages
    } yield messages mustBe empty
  }

  it should "give up on a send that doesn't finish in time" in runIO {
    val stuckTransport = new FallbackSyncTransport[IO] {
      override def send(messages: List[MainToFallbackMessage]): IO[Unit] = IO.never
    }

    new TransportFallbackUserRevoker[IO](stuckTransport, timeout = 50.millis)
      .revoke(user)
      .timeout(5.seconds)
  }

  "FallbackUserRevoker.noOp" should "do nothing" in runIO {
    FallbackUserRevoker.noOp[IO].revoke(user)
  }
}
