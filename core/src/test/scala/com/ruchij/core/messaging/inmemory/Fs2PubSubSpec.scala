package com.ruchij.core.messaging.inmemory

import cats.Id
import cats.effect.IO
import com.ruchij.core.messaging.models.CommittableRecord
import com.ruchij.core.test.IOSupport.runIO
import fs2.Stream
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

class Fs2PubSubSpec extends AnyFlatSpec with Matchers {

  "Fs2PubSub.apply" should "create a new Fs2PubSub instance" in runIO {
    Fs2PubSub[IO, String].map { pubSub =>
      pubSub must not be null
    }
  }

  "publishOne and subscribe" should "publish and receive messages" in runIO {
    for {
      pubSub <- Fs2PubSub[IO, String]

      // Start receiving from topic before publishing
      receivedFiber <- pubSub.subscribe("test-group").take(1).compile.toList
        .timeout(2.seconds)
        .start

      // Give subscription time to start
      _ <- IO.sleep(50.millis)

      // Publish a message
      _ <- pubSub.publishOne("test-message")

      received <- receivedFiber.joinWithNever

      _ <- IO.delay {
        received must have length 1
        received.head.value mustBe "test-message"
      }
    } yield ()
  }

  "publish" should "publish multiple messages via stream" in runIO {
    for {
      pubSub <- Fs2PubSub[IO, Int]

      // Start receiving
      receivedFiber <- pubSub.subscribe("test-group").take(3).compile.toList
        .timeout(2.seconds)
        .start

      _ <- IO.sleep(50.millis)

      // Publish multiple messages
      _ <- Stream.emits[IO, Int](List(1, 2, 3)).through(pubSub.publish).compile.drain

      received <- receivedFiber.joinWithNever

      _ <- IO.delay {
        received must have length 3
        received.map(_.value) mustBe List(1, 2, 3)
      }
    } yield ()
  }

  "subscribe" should "return CommittableRecords" in runIO {
    for {
      pubSub <- Fs2PubSub[IO, String]

      receivedFiber <- pubSub.subscribe("test-group").take(1).compile.toList
        .timeout(2.seconds)
        .start

      _ <- IO.sleep(50.millis)
      _ <- pubSub.publishOne("committable-message")

      received <- receivedFiber.joinWithNever

      _ <- IO.delay {
        received.head.value mustBe "committable-message"
        received.head.raw mustBe "committable-message"
      }
    } yield ()
  }

  "commit" should "succeed for any values (no-op)" in runIO {
    for {
      pubSub <- Fs2PubSub[IO, String]

      records = List(
        CommittableRecord[Id, String]("msg1", "msg1"),
        CommittableRecord[Id, String]("msg2", "msg2")
      )

      // commit should succeed without error
      _ <- pubSub.commit(records)
    } yield ()
  }

  "multiple subscribers" should "each receive messages" in runIO {
    for {
      pubSub <- Fs2PubSub[IO, String]

      // Start two subscribers
      receivedFiber1 <- pubSub.subscribe("group-1").take(1).compile.toList
        .timeout(2.seconds)
        .start

      receivedFiber2 <- pubSub.subscribe("group-2").take(1).compile.toList
        .timeout(2.seconds)
        .start

      _ <- IO.sleep(50.millis)

      _ <- pubSub.publishOne("broadcast-message")

      received1 <- receivedFiber1.joinWithNever
      received2 <- receivedFiber2.joinWithNever

      _ <- IO.delay {
        received1 must have length 1
        received2 must have length 1
        received1.head.value mustBe "broadcast-message"
        received2.head.value mustBe "broadcast-message"
      }
    } yield ()
  }

  "publishOne" should "return Unit" in runIO {
    for {
      pubSub <- Fs2PubSub[IO, String]
      result <- pubSub.publishOne("message")
      _ <- IO.delay {
        result mustBe (())
      }
    } yield ()
  }
}
