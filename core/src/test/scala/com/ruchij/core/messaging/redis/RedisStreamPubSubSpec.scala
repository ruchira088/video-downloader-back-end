package com.ruchij.core.messaging.redis

import cats.effect.IO
import com.ruchij.core.external.containers.RedisContainer
import com.ruchij.core.test.IOSupport.runIO
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log.Stdout.instance
import dev.profunktor.redis4cats.streams.RedisStream
import dev.profunktor.redis4cats.streams.data.XAddMessage
import fs2.Stream
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps

class RedisStreamPubSubSpec extends AnyFlatSpec with Matchers {

  import RedisStreamPubSubSpec._

  "RedisStreamPublisher.publishOne" should "publish a single message to a Redis stream" in runIO {
    RedisContainer.create[IO]
      .flatMap { redisConfiguration =>
        for {
          publisher <- RedisStreamPublisher.create[IO, TestMessage](redisConfiguration)
          subscriber <- RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration)
        } yield (publisher, subscriber)
      }
      .use { case (publisher, subscriber) =>
        for {
          receivedFiber <- subscriber.subscribe("test-group").take(1).compile.toList
            .timeout(15 seconds)
            .start

          _ <- IO.sleep(2 seconds)
          _ <- publisher.publishOne(TestMessage("hello", 42))

          received <- receivedFiber.joinWithNever

          _ <- IO.delay {
            received must have length 1
            received.head mustBe TestMessage("hello", 42)
          }
        } yield ()
      }
  }

  "RedisStreamPublisher.publish" should "publish multiple messages via a stream pipe" in runIO {
    RedisContainer.create[IO]
      .flatMap { redisConfiguration =>
        for {
          publisher <- RedisStreamPublisher.create[IO, TestMessage](redisConfiguration)
          subscriber <- RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration)
        } yield (publisher, subscriber)
      }
      .use { case (publisher, subscriber) =>
        val messages = (0 until 5).map(i => TestMessage(s"msg-$i", i)).toList

        for {
          receivedFiber <- subscriber.subscribe("test-group").take(5).compile.toList
            .timeout(15 seconds)
            .start

          _ <- IO.sleep(2 seconds)

          _ <- Stream.emits[IO, TestMessage](messages)
            .through(publisher.publish)
            .compile
            .drain

          received <- receivedFiber.joinWithNever

          _ <- IO.delay {
            received must have length 5
            received.map(_.index).toSet mustBe Set(0, 1, 2, 3, 4)
            received.map(_.name) mustBe messages.map(_.name)
          }
        } yield ()
      }
  }

  "RedisStreamPublisher.publish" should "handle a large batch of messages" in runIO {
    RedisContainer.create[IO]
      .flatMap { redisConfiguration =>
        for {
          publisher <- RedisStreamPublisher.create[IO, TestMessage](redisConfiguration)
          subscriber <- RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration)
        } yield (publisher, subscriber)
      }
      .use { case (publisher, subscriber) =>
        val count = 50

        for {
          receivedFiber <- subscriber.subscribe("test-group").take(count.toLong).compile.toList
            .timeout(30 seconds)
            .start

          _ <- IO.sleep(2 seconds)

          _ <- Stream.range[IO, Int](0, count)
            .map(i => TestMessage(s"batch-$i", i))
            .through(publisher.publish)
            .compile
            .drain

          received <- receivedFiber.joinWithNever

          _ <- IO.delay {
            received must have length count
            received.map(_.index).toSet mustBe Range(0, count).toSet
          }
        } yield ()
      }
  }

  "RedisStreamSubscriber.subscribe" should "deserialize messages correctly preserving all fields" in runIO {
    RedisContainer.create[IO]
      .flatMap { redisConfiguration =>
        for {
          publisher <- RedisStreamPublisher.create[IO, TestMessage](redisConfiguration)
          subscriber <- RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration)
        } yield (publisher, subscriber)
      }
      .use { case (publisher, subscriber) =>
        val original = TestMessage("special chars: é ñ ü 日本語", 999)

        for {
          receivedFiber <- subscriber.subscribe("test-group").take(1).compile.toList
            .timeout(15 seconds)
            .start

          _ <- IO.sleep(2 seconds)
          _ <- publisher.publishOne(original)

          received <- receivedFiber.joinWithNever

          _ <- IO.delay {
            received.head.name mustBe original.name
            received.head.index mustBe original.index
          }
        } yield ()
      }
  }

  "RedisStreamSubscriber.commit" should "succeed as a no-op for any values" in runIO {
    RedisContainer.create[IO]
      .flatMap { redisConfiguration =>
        RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration)
      }
      .use { subscriber =>
        for {
          _ <- subscriber.commit(List(TestMessage("a", 1), TestMessage("b", 2)))
        } yield ()
      }
  }

  "Multiple subscribers" should "each independently receive messages from the stream" in runIO {
    RedisContainer.create[IO]
      .flatMap { redisConfiguration =>
        for {
          publisher <- RedisStreamPublisher.create[IO, TestMessage](redisConfiguration)
          subscriber1 <- RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration)
          subscriber2 <- RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration)
        } yield (publisher, subscriber1, subscriber2)
      }
      .use { case (publisher, subscriber1, subscriber2) =>
        for {
          fiber1 <- subscriber1.subscribe("group-1").take(3).compile.toList
            .timeout(15 seconds)
            .start

          fiber2 <- subscriber2.subscribe("group-2").take(3).compile.toList
            .timeout(15 seconds)
            .start

          _ <- IO.sleep(2 seconds)

          _ <- Stream.emits[IO, TestMessage](
            List(TestMessage("x", 1), TestMessage("y", 2), TestMessage("z", 3))
          ).through(publisher.publish).compile.drain

          received1 <- fiber1.joinWithNever
          received2 <- fiber2.joinWithNever

          _ <- IO.delay {
            received1 must have length 3
            received2 must have length 3
            received1.map(_.name).toSet mustBe Set("x", "y", "z")
            received2.map(_.name).toSet mustBe Set("x", "y", "z")
          }
        } yield ()
      }
  }

  "RedisStreamPublisher and Subscriber" should "work with different message types using separate stream keys" in runIO {
    RedisContainer.create[IO]
      .flatMap { redisConfiguration =>
        for {
          stringPublisher <- RedisStreamPublisher.create[IO, SimpleMessage](redisConfiguration)
          stringSubscriber <- RedisStreamSubscriber.create[IO, SimpleMessage](redisConfiguration)
          testPublisher <- RedisStreamPublisher.create[IO, TestMessage](redisConfiguration)
          testSubscriber <- RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration)
        } yield (stringPublisher, stringSubscriber, testPublisher, testSubscriber)
      }
      .use { case (simplePublisher, simpleSubscriber, testPublisher, testSubscriber) =>
        for {
          simpleFiber <- simpleSubscriber.subscribe("group-1").take(1).compile.toList
            .timeout(15 seconds)
            .start

          testFiber <- testSubscriber.subscribe("group-1").take(1).compile.toList
            .timeout(15 seconds)
            .start

          _ <- IO.sleep(2 seconds)

          _ <- simplePublisher.publishOne(SimpleMessage("simple-value"))
          _ <- testPublisher.publishOne(TestMessage("test-value", 100))

          simpleReceived <- simpleFiber.joinWithNever
          testReceived <- testFiber.joinWithNever

          _ <- IO.delay {
            simpleReceived must have length 1
            simpleReceived.head.value mustBe "simple-value"

            testReceived must have length 1
            testReceived.head.name mustBe "test-value"
            testReceived.head.index mustBe 100
          }
        } yield ()
      }
  }

  "RedisStreamPublisher.publishOne" should "return Unit" in runIO {
    RedisContainer.create[IO]
      .flatMap { redisConfiguration =>
        RedisStreamPublisher.create[IO, TestMessage](redisConfiguration)
      }
      .use { publisher =>
        for {
          result <- publisher.publishOne(TestMessage("test", 1))
          _ <- IO.delay {
            result mustBe ()
          }
        } yield ()
      }
  }

  "RedisStreamPublisher.publish" should "handle an empty stream without errors" in runIO {
    RedisContainer.create[IO]
      .flatMap { redisConfiguration =>
        RedisStreamPublisher.create[IO, TestMessage](redisConfiguration)
      }
      .use { publisher =>
        Stream.empty
          .covary[IO]
          .through(publisher.publish)
          .compile
          .drain
      }
  }

  "RedisStreamSubscriber.subscribe" should "use the correct stream key from RedisStreamTopic" in runIO {
    RedisContainer.create[IO]
      .flatMap { redisConfiguration =>
        for {
          publisher <- RedisStreamPublisher.create[IO, TestMessage](redisConfiguration)
          subscriber <- RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration)
        } yield (publisher, subscriber)
      }
      .use { case (publisher, subscriber) =>
        for {
          receivedFiber <- subscriber.subscribe("any-group").take(1).compile.toList
            .timeout(15 seconds)
            .start

          _ <- IO.sleep(2 seconds)
          _ <- publisher.publishOne(TestMessage("routed", 7))

          received <- receivedFiber.joinWithNever

          _ <- IO.delay {
            received must have length 1
            received.head mustBe TestMessage("routed", 7)
          }
        } yield ()
      }
  }

  "RedisStreamPublisher.create" should "create a publisher from RedisConfiguration" in runIO {
    RedisContainer.create[IO]
      .flatMap { redisConfiguration =>
        RedisStreamPublisher.create[IO, TestMessage](redisConfiguration)
      }
      .use { publisher =>
        IO.delay {
          publisher must not be null
        }
      }
  }

  "RedisStreamSubscriber.create" should "create a subscriber from RedisConfiguration" in runIO {
    RedisContainer.create[IO]
      .flatMap { redisConfiguration =>
        RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration)
      }
      .use { subscriber =>
        IO.delay {
          subscriber must not be null
        }
      }
  }

  "RedisStreamSubscriber.subscribe" should "skip messages with missing data key and continue" in runIO {
    val streamKey = implicitly[RedisStreamTopic[TestMessage]].streamKey

    RedisContainer.create[IO]
      .flatMap { redisConfiguration =>
        for {
          rawStream <- Redis[IO].utf8(redisConfiguration.uri).map(cmd => RedisStream[IO, String, String](cmd))
          publisher <- RedisStreamPublisher.create[IO, TestMessage](redisConfiguration)
          subscriber <- RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration)
        } yield (rawStream, publisher, subscriber)
      }
      .use { case (rawStream, publisher, subscriber) =>
        for {
          // Subscribe expecting only the valid message (malformed ones get skipped)
          receivedFiber <- subscriber.subscribe("test-group").take(1).compile.toList
            .timeout(15 seconds)
            .start

          _ <- IO.sleep(2 seconds)

          // Inject a message with missing "data" key
          _ <- rawStream.append {
            Stream.emit(XAddMessage(streamKey, Map("wrong-key" -> "some-value")))
          }.compile.drain

          // Publish a valid message after the malformed one
          _ <- publisher.publishOne(TestMessage("valid-after-missing-key", 1))

          received <- receivedFiber.joinWithNever

          _ <- IO.delay {
            received must have length 1
            received.head mustBe TestMessage("valid-after-missing-key", 1)
          }
        } yield ()
      }
  }

  it should "skip messages with invalid JSON in the data key and continue" in runIO {
    val streamKey = implicitly[RedisStreamTopic[TestMessage]].streamKey

    RedisContainer.create[IO]
      .flatMap { redisConfiguration =>
        for {
          rawStream <- Redis[IO].utf8(redisConfiguration.uri).map(cmd => RedisStream[IO, String, String](cmd))
          publisher <- RedisStreamPublisher.create[IO, TestMessage](redisConfiguration)
          subscriber <- RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration)
        } yield (rawStream, publisher, subscriber)
      }
      .use { case (rawStream, publisher, subscriber) =>
        for {
          receivedFiber <- subscriber.subscribe("test-group").take(1).compile.toList
            .timeout(15 seconds)
            .start

          _ <- IO.sleep(2 seconds)

          // Inject a message with malformed JSON in "data" key
          _ <- rawStream.append {
            Stream.emit(XAddMessage(streamKey, Map("data" -> "not valid json {{{}")))
          }.compile.drain

          _ <- publisher.publishOne(TestMessage("valid-after-bad-json", 2))

          received <- receivedFiber.joinWithNever

          _ <- IO.delay {
            received must have length 1
            received.head mustBe TestMessage("valid-after-bad-json", 2)
          }
        } yield ()
      }
  }

  it should "skip messages with valid JSON but wrong schema and continue" in runIO {
    val streamKey = implicitly[RedisStreamTopic[TestMessage]].streamKey

    RedisContainer.create[IO]
      .flatMap { redisConfiguration =>
        for {
          rawStream <- Redis[IO].utf8(redisConfiguration.uri).map(cmd => RedisStream[IO, String, String](cmd))
          publisher <- RedisStreamPublisher.create[IO, TestMessage](redisConfiguration)
          subscriber <- RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration)
        } yield (rawStream, publisher, subscriber)
      }
      .use { case (rawStream, publisher, subscriber) =>
        for {
          receivedFiber <- subscriber.subscribe("test-group").take(1).compile.toList
            .timeout(15 seconds)
            .start

          _ <- IO.sleep(2 seconds)

          // Inject valid JSON but missing required fields for TestMessage
          _ <- rawStream.append {
            Stream.emit(XAddMessage(streamKey, Map("data" -> """{"unknown_field": true}""")))
          }.compile.drain

          _ <- publisher.publishOne(TestMessage("valid-after-wrong-schema", 3))

          received <- receivedFiber.joinWithNever

          _ <- IO.delay {
            received must have length 1
            received.head mustBe TestMessage("valid-after-wrong-schema", 3)
          }
        } yield ()
      }
  }

  it should "skip multiple consecutive malformed messages and still receive valid ones" in runIO {
    val streamKey = implicitly[RedisStreamTopic[TestMessage]].streamKey

    RedisContainer.create[IO]
      .flatMap { redisConfiguration =>
        for {
          rawStream <- Redis[IO].utf8(redisConfiguration.uri).map(cmd => RedisStream[IO, String, String](cmd))
          publisher <- RedisStreamPublisher.create[IO, TestMessage](redisConfiguration)
          subscriber <- RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration)
        } yield (rawStream, publisher, subscriber)
      }
      .use { case (rawStream, publisher, subscriber) =>
        for {
          receivedFiber <- subscriber.subscribe("test-group").take(2).compile.toList
            .timeout(20 seconds)
            .start

          _ <- IO.sleep(2 seconds)

          // Send several malformed messages in a row
          _ <- rawStream.append {
            Stream.emits(List(
              XAddMessage(streamKey, Map("wrong-key" -> "value1")),
              XAddMessage(streamKey, Map("data" -> "broken json")),
              XAddMessage(streamKey, Map("data" -> """{"bad": "schema"}""")),
              XAddMessage(streamKey, Map("random" -> "garbage"))
            ))
          }.compile.drain

          // Then send valid messages
          _ <- publisher.publishOne(TestMessage("survivor-1", 10))
          _ <- publisher.publishOne(TestMessage("survivor-2", 20))

          received <- receivedFiber.joinWithNever

          _ <- IO.delay {
            received must have length 2
            received.map(_.name) mustBe List("survivor-1", "survivor-2")
            received.map(_.index) mustBe List(10, 20)
          }
        } yield ()
      }
  }

  it should "handle interleaved valid and malformed messages" in runIO {
    val streamKey = implicitly[RedisStreamTopic[TestMessage]].streamKey

    RedisContainer.create[IO]
      .flatMap { redisConfiguration =>
        for {
          rawStream <- Redis[IO].utf8(redisConfiguration.uri).map(cmd => RedisStream[IO, String, String](cmd))
          publisher <- RedisStreamPublisher.create[IO, TestMessage](redisConfiguration)
          subscriber <- RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration)
        } yield (rawStream, publisher, subscriber)
      }
      .use { case (rawStream, publisher, subscriber) =>
        for {
          receivedFiber <- subscriber.subscribe("test-group").take(3).compile.toList
            .timeout(20 seconds)
            .start

          _ <- IO.sleep(2 seconds)

          // Interleave: valid, malformed, valid, malformed, valid
          _ <- publisher.publishOne(TestMessage("first", 1))

          _ <- rawStream.append {
            Stream.emit(XAddMessage(streamKey, Map("data" -> "not json")))
          }.compile.drain

          _ <- publisher.publishOne(TestMessage("second", 2))

          _ <- rawStream.append {
            Stream.emit(XAddMessage(streamKey, Map("wrong" -> "key")))
          }.compile.drain

          _ <- publisher.publishOne(TestMessage("third", 3))

          received <- receivedFiber.joinWithNever

          _ <- IO.delay {
            received must have length 3
            received.map(_.name) mustBe List("first", "second", "third")
          }
        } yield ()
      }
  }

  it should "skip messages with empty data value" in runIO {
    val streamKey = implicitly[RedisStreamTopic[TestMessage]].streamKey

    RedisContainer.create[IO]
      .flatMap { redisConfiguration =>
        for {
          rawStream <- Redis[IO].utf8(redisConfiguration.uri).map(cmd => RedisStream[IO, String, String](cmd))
          publisher <- RedisStreamPublisher.create[IO, TestMessage](redisConfiguration)
          subscriber <- RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration)
        } yield (rawStream, publisher, subscriber)
      }
      .use { case (rawStream, publisher, subscriber) =>
        for {
          receivedFiber <- subscriber.subscribe("test-group").take(1).compile.toList
            .timeout(15 seconds)
            .start

          _ <- IO.sleep(2 seconds)

          // Inject a message with empty string as data value
          _ <- rawStream.append {
            Stream.emit(XAddMessage(streamKey, Map("data" -> "")))
          }.compile.drain

          _ <- publisher.publishOne(TestMessage("valid-after-empty", 99))

          received <- receivedFiber.joinWithNever

          _ <- IO.delay {
            received must have length 1
            received.head mustBe TestMessage("valid-after-empty", 99)
          }
        } yield ()
      }
  }

  "Publish then subscribe ordering" should "only receive messages published after subscription starts" in runIO {
    RedisContainer.create[IO]
      .flatMap { redisConfiguration =>
        for {
          publisher <- RedisStreamPublisher.create[IO, TestMessage](redisConfiguration)
          subscriber <- RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration)
        } yield (publisher, subscriber)
      }
      .use { case (publisher, subscriber) =>
        for {
          // Publish before subscribing
          _ <- publisher.publishOne(TestMessage("before-subscribe", 0))
          _ <- IO.sleep(1 second)

          // Now subscribe and publish new messages
          receivedFiber <- subscriber.subscribe("test-group").take(2).compile.toList
            .timeout(15 seconds)
            .start

          _ <- IO.sleep(2 seconds)

          _ <- publisher.publishOne(TestMessage("after-subscribe-1", 1))
          _ <- publisher.publishOne(TestMessage("after-subscribe-2", 2))

          received <- receivedFiber.joinWithNever

          _ <- IO.delay {
            received must have length 2
            // Should NOT contain the message published before subscribing
            received.map(_.name) must not contain "before-subscribe"
            received.map(_.name) must contain allOf("after-subscribe-1", "after-subscribe-2")
          }
        } yield ()
      }
  }
}

object RedisStreamPubSubSpec {
  final case class TestMessage(name: String, index: Int)

  object TestMessage {
    implicit val testMessageCodec: Codec.AsObject[TestMessage] = deriveCodec[TestMessage]
  }

  final case class SimpleMessage(value: String)

  object SimpleMessage {
    implicit val simpleMessageCodec: Codec.AsObject[SimpleMessage] = deriveCodec[SimpleMessage]
  }
}
