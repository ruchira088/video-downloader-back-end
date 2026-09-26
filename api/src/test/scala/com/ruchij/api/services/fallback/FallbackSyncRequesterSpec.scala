package com.ruchij.api.services.fallback

import cats.effect.IO
import com.ruchij.api.services.fallback.FallbackSyncStubs.{FailingPublisher, RecordingPublisher}
import com.ruchij.api.services.fallback.models.FallbackSyncRequest
import com.ruchij.core.messaging.Publisher
import com.ruchij.core.test.IOSupport.runIO
import fs2.Pipe
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class FallbackSyncRequesterSpec extends AnyFlatSpec with Matchers {

  "FallbackSyncRequester" should "publish a sync request for the video" in runIO {
    for {
      publisher <- RecordingPublisher[FallbackSyncRequest]
      _ <- new FallbackSyncRequester[IO](publisher).request("video-1")
      published <- publisher.messages
    } yield published mustBe List(FallbackSyncRequest("video-1"))
  }

  it should "not fail when the publisher fails" in runIO {
    new FallbackSyncRequester[IO](new FailingPublisher[FallbackSyncRequest]).request("video-1").attempt.map {
      result => result mustBe Right(())
    }
  }

  it should "give up instead of blocking when the publisher hangs" in runIO {
    val hangingPublisher = new Publisher[IO, FallbackSyncRequest] {
      override val publish: Pipe[IO, FallbackSyncRequest, Unit] = _.evalMap(publishOne)

      override def publishOne(input: FallbackSyncRequest): IO[Unit] = IO.never
    }

    new FallbackSyncRequester[IO](hangingPublisher, timeout = 50.millis)
      .request("video-1")
      .timeout(5.seconds)
      .attempt
      .map(result => result mustBe Right(()))
  }
}
