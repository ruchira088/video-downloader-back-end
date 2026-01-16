package com.ruchij.core.messaging

import cats.{Foldable, Functor, Id}
import cats.effect.IO
import com.ruchij.core.test.IOSupport.runIO
import fs2.{Pipe, Stream}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class PubSubSpec extends AnyFlatSpec with Matchers {

  class TestPublisher extends Publisher[IO, String] {
    var publishedMessages: List[String] = List.empty

    override val publish: Pipe[IO, String, Unit] = { input =>
      input.evalMap { msg =>
        IO.delay { publishedMessages = publishedMessages :+ msg }
      }
    }

    override def publishOne(input: String): IO[Unit] =
      IO.delay { publishedMessages = publishedMessages :+ input }
  }

  class TestSubscriber extends Subscriber[IO, Id, String] {
    var subscriptions: List[String] = List.empty
    var committed: List[Id[String]] = List.empty

    override def subscribe(groupId: String): Stream[IO, Id[String]] = {
      subscriptions = subscriptions :+ groupId
      Stream.emit[IO, String]("test-message")
    }

    override def commit[H[_]: Foldable: Functor](values: H[Id[String]]): IO[Unit] =
      IO.delay { committed = committed ++ Foldable[H].toList(values) }
  }

  "PubSub.from" should "create a PubSub from publisher and subscriber" in runIO {
    val publisher = new TestPublisher
    val subscriber = new TestSubscriber

    val pubSub = PubSub.from[IO, Id, String](publisher, subscriber)

    for {
      // Test publishOne
      _ <- pubSub.publishOne("message-1")
      _ <- IO.delay {
        publisher.publishedMessages mustBe List("message-1")
      }

      // Test publish
      _ <- Stream.emit[IO, String]("message-2").through(pubSub.publish).compile.drain
      _ <- IO.delay {
        publisher.publishedMessages mustBe List("message-1", "message-2")
      }

      // Test subscribe
      received <- pubSub.subscribe("test-group").compile.toList
      _ <- IO.delay {
        subscriber.subscriptions mustBe List("test-group")
        received mustBe List("test-message")
      }

      // Test commit
      _ <- pubSub.commit(List("committed-1", "committed-2"))
      _ <- IO.delay {
        subscriber.committed mustBe List("committed-1", "committed-2")
      }
    } yield ()
  }

  it should "delegate all operations to underlying publisher and subscriber" in runIO {
    val publisher = new TestPublisher
    val subscriber = new TestSubscriber

    val pubSub = PubSub.from[IO, Id, String](publisher, subscriber)

    for {
      // Multiple publish operations
      _ <- pubSub.publishOne("a")
      _ <- pubSub.publishOne("b")
      _ <- pubSub.publishOne("c")
      _ <- IO.delay {
        publisher.publishedMessages mustBe List("a", "b", "c")
      }

      // Multiple subscribe operations
      _ <- pubSub.subscribe("group-1").compile.toList
      _ <- pubSub.subscribe("group-2").compile.toList
      _ <- IO.delay {
        subscriber.subscriptions mustBe List("group-1", "group-2")
      }
    } yield ()
  }
}
