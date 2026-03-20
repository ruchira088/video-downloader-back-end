package com.ruchij.core.messaging

import cats.effect.{IO, Resource}
import cats.implicits._
import com.ruchij.core.test.IOSupport.runIO
import fs2.Stream
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps

trait PublisherSubscriberSpec[G[_]] { self: AnyFlatSpec with Matchers =>

  import PublisherSubscriberSpec._

  def resource: Resource[IO, (Publisher[IO, TestMessage], Subscriber[IO, G, TestMessage])]

  def extractValue(ga: G[TestMessage]): TestMessage

  def testTimeout: FiniteDuration = 30 seconds

  "Publisher and Subscriber" should "publish a single message and receive it" in runIO {
    resource.use {
      case (publisher, subscriber) =>
        for {
          receivedFiber <- subscriber
            .subscribe("test-group")
            .take(1)
            .compile
            .toList
            .timeout(testTimeout)
            .start

          _ <- IO.sleep(2 seconds)
          _ <- publisher.publishOne(TestMessage("hello", 42))

          received <- receivedFiber.joinWithNever

          _ <- IO.delay {
            received must have length 1
            extractValue(received.head) mustBe TestMessage("hello", 42)
          }
        } yield ()
    }
  }

  it should "publish multiple messages via the stream pipe" in runIO {
    resource.use {
      case (publisher, subscriber) =>
        val messages = (0 until 5).map(i => TestMessage(s"msg-$i", i)).toList

        for {
          receivedFiber <- subscriber
            .subscribe("test-group")
            .take(5)
            .compile
            .toList
            .timeout(testTimeout)
            .start

          _ <- IO.sleep(2 seconds)

          _ <- Stream
            .emits[IO, TestMessage](messages)
            .through(publisher.publish)
            .compile
            .drain

          received <- receivedFiber.joinWithNever

          _ <- IO.delay {
            received must have length 5
            received.map(ga => extractValue(ga).index).toSet mustBe Set(0, 1, 2, 3, 4)
            received.map(ga => extractValue(ga).name) mustBe messages.map(_.name)
          }
        } yield ()
    }
  }

  it should "handle a large batch of messages" in runIO {
    resource.use {
      case (publisher, subscriber) =>
        val count = 50

        for {
          receivedFiber <- subscriber
            .subscribe("test-group")
            .take(count.toLong)
            .compile
            .toList
            .timeout(testTimeout * 2)
            .start

          _ <- IO.sleep(2 seconds)

          _ <- Stream
            .range[IO, Int](0, count)
            .map(i => TestMessage(s"batch-$i", i))
            .through(publisher.publish)
            .compile
            .drain

          received <- receivedFiber.joinWithNever

          _ <- IO.delay {
            received must have length count
            received.map(ga => extractValue(ga).index).toSet mustBe Range(0, count).toSet
          }
        } yield ()
    }
  }

  it should "handle an empty stream without errors" in runIO {
    resource.use {
      case (publisher, _) =>
        Stream.empty
          .covary[IO]
          .through(publisher.publish)
          .compile
          .drain
    }
  }

  it should "preserve message ordering" in runIO {
    resource.use {
      case (publisher, subscriber) =>
        val messages = (0 until 10).map(i => TestMessage(s"ordered-$i", i)).toList

        for {
          receivedFiber <- subscriber
            .subscribe("test-group")
            .take(10)
            .compile
            .toList
            .timeout(testTimeout)
            .start

          _ <- IO.sleep(2 seconds)

          _ <- messages.traverse_(publisher.publishOne)

          received <- receivedFiber.joinWithNever

          _ <- IO.delay {
            received must have length 10
            received.map(ga => extractValue(ga).index) mustBe (0 until 10).toList
          }
        } yield ()
    }
  }

  it should "deserialize messages correctly preserving all fields" in runIO {
    resource.use {
      case (publisher, subscriber) =>
        val original = TestMessage("special chars: é ñ ü 日本語", 999)

        for {
          receivedFiber <- subscriber
            .subscribe("test-group")
            .take(1)
            .compile
            .toList
            .timeout(testTimeout)
            .start

          _ <- IO.sleep(2 seconds)
          _ <- publisher.publishOne(original)

          received <- receivedFiber.joinWithNever

          _ <- IO.delay {
            val msg = extractValue(received.head)
            msg.name mustBe original.name
            msg.index mustBe original.index
          }
        } yield ()
    }
  }
}

object PublisherSubscriberSpec {
  final case class TestMessage(name: String, index: Int)

  object TestMessage {
    implicit val testMessageCodec: Codec.AsObject[TestMessage] = deriveCodec[TestMessage]
  }
}
