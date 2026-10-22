package com.ruchij.core.messaging

import cats.effect.{IO, Ref, Resource}
import cats.implicits._
import com.ruchij.core.test.IOSupport.runIO
import fs2.Stream
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import org.scalatest.flatspec.AnyFlatSpec
import vulcan.generic._
import org.scalatest.matchers.must.Matchers

import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.language.postfixOps

trait PublisherSubscriberSpec { self: AnyFlatSpec with Matchers =>

  import PublisherSubscriberSpec._

  /**
    * The publisher and subscriber, together with how the tests make sure a subscription can receive before they
    * publish. A fixed delay is not enough: every backend starts a new subscription from the latest message, so a
    * message published before the subscription has started is lost, and a slow CI runner makes that likely.
    */
  def fixture: Resource[IO, PubSubFixture]

  def testTimeout: FiniteDuration = 30 seconds

  /** Whether the backend implements consumer-group semantics, i.e. what the Kafka backend does. */
  def supportsConsumerGroups: Boolean = true

  private def consumeInto(
    subscriber: Subscriber[IO, TestMessage],
    groupId: String,
    sink: Ref[IO, List[TestMessage]]
  ): Stream[IO, Unit] =
    subscriber
      .subscribe(groupId)
      .evalMap { record =>
        subscriber.commit(List(record)) *> sink.update(_ :+ subscriber.extractValue(record))
      }

  protected def waitUntil(condition: IO[Boolean]): IO[Unit] =
    condition.flatMap { satisfied =>
      if (satisfied) IO.unit else IO.sleep(100 milliseconds) *> waitUntil(condition)
    }

  protected def publishAll(publisher: Publisher[IO, TestMessage], prefix: String, indices: Seq[Int]): IO[Unit] =
    within(s"Publishing the $prefix messages") {
      indices.toList.traverse_(index => publisher.publishOne(TestMessage(s"$prefix-$index", index)))
    }

  /** Bounds a step of a test, so that a step that stalls fails promptly and says which step it was. */
  protected def within[A](step: String)(io: IO[A]): IO[A] =
    io.timeoutTo(testTimeout, IO.raiseError(new TimeoutException(s"$step did not complete within $testTimeout")))

  "Publisher and Subscriber" should "publish a single message and receive it" in runIO {
    fixture.use {
      case PubSubFixture(publisher, subscriber, readiness) =>
        for {
          _ <- readiness.prepare(partitions = 1, "test-group")
          receivedFiber <- subscriber
            .subscribe("test-group")
            .map(subscriber.extractValue)
            .take(1)
            .compile
            .toList
            .timeout(testTimeout)
            .start

          _ <- readiness.awaitReady("test-group" -> 1)
          _ <- within("Publishing the message")(publisher.publishOne(TestMessage("hello", 42)))

          received <- within("Receiving the messages")(receivedFiber.joinWithNever)

          _ <- IO.delay {
            received must have length 1
            received.head mustBe TestMessage("hello", 42)
          }
        } yield ()
    }
  }

  it should "publish multiple messages via the stream pipe" in runIO {
    fixture.use {
      case PubSubFixture(publisher, subscriber, readiness) =>
        val messages = (0 until 5).map(i => TestMessage(s"msg-$i", i)).toList

        for {
          _ <- readiness.prepare(partitions = 1, "test-group")
          receivedFiber <- subscriber
            .subscribe("test-group")
            .map(subscriber.extractValue)
            .take(5)
            .compile
            .toList
            .timeout(testTimeout)
            .start

          _ <- readiness.awaitReady("test-group" -> 1)

          _ <- within("Publishing the messages") {
            Stream
              .emits[IO, TestMessage](messages)
              .through(publisher.publish)
              .compile
              .drain
          }

          received <- within("Receiving the messages")(receivedFiber.joinWithNever)

          _ <- IO.delay {
            received must have length 5
            received.map(_.index).toSet mustBe Set(0, 1, 2, 3, 4)
            received.map(_.name) mustBe messages.map(_.name)
          }
        } yield ()
    }
  }

  it should "handle a large batch of messages" in runIO {
    fixture.use {
      case PubSubFixture(publisher, subscriber, readiness) =>
        val count = 50

        for {
          _ <- readiness.prepare(partitions = 1, "test-group")
          receivedFiber <- subscriber
            .subscribe("test-group")
            .map(subscriber.extractValue)
            .take(count.toLong)
            .compile
            .toList
            .timeout(testTimeout * 2)
            .start

          _ <- readiness.awaitReady("test-group" -> 1)

          _ <- within("Publishing the messages") {
            Stream
              .range[IO, Int](0, count)
              .map(i => TestMessage(s"batch-$i", i))
              .through(publisher.publish)
              .compile
              .drain
          }

          received <- within("Receiving the messages")(receivedFiber.joinWithNever)

          _ <- IO.delay {
            received must have length count
            received.map(_.index).toSet mustBe Range(0, count).toSet
          }
        } yield ()
    }
  }

  it should "handle an empty stream without errors" in runIO {
    fixture.use {
      case PubSubFixture(publisher, _, _) =>
        Stream.empty
          .covary[IO]
          .through(publisher.publish)
          .compile
          .drain
    }
  }

  it should "preserve message ordering" in runIO {
    fixture.use {
      case PubSubFixture(publisher, subscriber, readiness) =>
        val messages = (0 until 10).map(i => TestMessage(s"ordered-$i", i)).toList

        for {
          _ <- readiness.prepare(partitions = 1, "test-group")
          receivedFiber <- subscriber
            .subscribe("test-group")
            .map(subscriber.extractValue)
            .take(10)
            .compile
            .toList
            .timeout(testTimeout)
            .start

          _ <- readiness.awaitReady("test-group" -> 1)

          _ <- within("Publishing the messages")(messages.traverse_(publisher.publishOne))

          received <- within("Receiving the messages")(receivedFiber.joinWithNever)

          _ <- IO.delay {
            received must have length 10
            received.map(_.index) mustBe (0 until 10).toList
          }
        } yield ()
    }
  }

  it should "deserialize messages correctly preserving all fields" in runIO {
    fixture.use {
      case PubSubFixture(publisher, subscriber, readiness) =>
        val original = TestMessage("special chars: é ñ ü 日本語", 999)

        for {
          _ <- readiness.prepare(partitions = 1, "test-group")
          receivedFiber <- subscriber
            .subscribe("test-group")
            .map(subscriber.extractValue)
            .take(1)
            .compile
            .toList
            .timeout(testTimeout)
            .start

          _ <- readiness.awaitReady("test-group" -> 1)
          _ <- within("Publishing the message")(publisher.publishOne(original))

          received <- within("Receiving the messages")(receivedFiber.joinWithNever)

          _ <- IO.delay {
            val msg = received.head
            msg.name mustBe original.name
            msg.index mustBe original.index
          }
        } yield ()
    }
  }

  if (supportsConsumerGroups) {
    it should "split messages between subscribers in the same group" in runIO {
      fixture.use {
        case PubSubFixture(publisher, subscriber, readiness) =>
          val count = 20

          for {
            first <- Ref.of[IO, List[TestMessage]](Nil)
            second <- Ref.of[IO, List[TestMessage]](Nil)

            // Two partitions, so that each subscriber in the group is assigned one
            _ <- readiness.prepare(partitions = 2, "shared-group")
            fiber <- consumeInto(subscriber, "shared-group", first)
              .merge(consumeInto(subscriber, "shared-group", second))
              .compile
              .drain
              .start

            _ <- readiness.awaitReady("shared-group" -> 2)
            _ <- publishAll(publisher, "split", 0 until count)

            _ <- waitUntil((first.get, second.get).mapN(_.size + _.size >= count)).timeout(testTimeout)
            // Give any duplicate deliveries time to surface
            _ <- IO.sleep(3 seconds)
            _ <- fiber.cancel

            firstReceived <- first.get
            secondReceived <- second.get

            _ <- IO.delay {
              (firstReceived ++ secondReceived).map(_.index).sorted mustBe (0 until count).toList
            }
          } yield ()
      }
    }

    it should "deliver every message to each group" in runIO {
      fixture.use {
        case PubSubFixture(publisher, subscriber, readiness) =>
          val count = 10

          for {
            groupA <- Ref.of[IO, List[TestMessage]](Nil)
            groupB <- Ref.of[IO, List[TestMessage]](Nil)

            _ <- readiness.prepare(partitions = 1, "group-a", "group-b")
            fiber <- consumeInto(subscriber, "group-a", groupA)
              .merge(consumeInto(subscriber, "group-b", groupB))
              .compile
              .drain
              .start

            _ <- readiness.awaitReady("group-a" -> 1, "group-b" -> 1)
            _ <- publishAll(publisher, "fan-out", 0 until count)

            _ <- waitUntil((groupA.get, groupB.get).mapN(_.size >= count && _.size >= count)).timeout(testTimeout)
            _ <- IO.sleep(2 seconds)
            _ <- fiber.cancel

            receivedA <- groupA.get
            receivedB <- groupB.get

            _ <- IO.delay {
              receivedA.map(_.index) mustBe (0 until count).toList
              receivedB.map(_.index) mustBe (0 until count).toList
            }
          } yield ()
      }
    }

    it should "resume a group from where it left off after a restart" in runIO {
      fixture.use {
        case PubSubFixture(publisher, subscriber, readiness) =>
          def consume(count: Int): IO[List[TestMessage]] =
            subscriber
              .subscribe("resume-group")
              .evalMap(record => subscriber.commit(List(record)).as(subscriber.extractValue(record)))
              .take(count.toLong)
              .compile
              .toList
              .timeout(testTimeout)

          for {
            _ <- readiness.prepare(partitions = 1, "resume-group")
            firstRunFiber <- consume(1).start
            _ <- readiness.awaitReady("resume-group" -> 1)
            _ <- publishAll(publisher, "before-restart", Seq(0))
            firstRun <- within("Receiving the first message")(firstRunFiber.joinWithNever)

            // Published while the group has no running subscriber
            _ <- publishAll(publisher, "during-restart", 1 to 3)

            secondRun <- consume(3)

            _ <- IO.delay {
              firstRun.map(_.index) mustBe List(0)
              secondRun.map(_.index) mustBe List(1, 2, 3)
            }
          } yield ()
      }
    }
  }
}

object PublisherSubscriberSpec {
  final case class TestMessage(name: String, index: Int)

  final case class PubSubFixture(
    publisher: Publisher[IO, TestMessage],
    subscriber: Subscriber[IO, TestMessage],
    readiness: SubscriptionReadiness
  )

  /** Lets a test wait for its subscriptions to be able to receive, instead of publishing into a race. */
  trait SubscriptionReadiness {

    /** Runs before anything subscribes to the given groups, on a topic with the given number of partitions. */
    def prepare(partitions: Int, groupIds: String*): IO[Unit]

    /** Completes once each group has the given number of subscribers, all ready to receive messages. */
    def awaitReady(subscribersByGroup: (String, Int)*): IO[Unit]
  }

  object TestMessage {
    implicit val testMessageCodec: Codec.AsObject[TestMessage] = deriveCodec[TestMessage]

    implicit val testMessageMessagingTopic: MessagingTopic[TestMessage] =
      new MessagingTopic[TestMessage] {
        override val name: String = "test-message-topic"
        override val avroCodec: vulcan.Codec[TestMessage] = vulcan.Codec.derive[TestMessage]
        override val jsonCodec: Codec[TestMessage] = testMessageCodec
      }
  }
}
