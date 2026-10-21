package com.ruchij.core.messaging.redis

import cats.effect.{IO, Resource}
import cats.implicits._
import com.ruchij.core.external.containers.RedisContainer
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage._
import com.ruchij.core.messaging.{Publisher, PublisherSubscriberSpec, Subscriber}
import com.ruchij.core.test.IOSupport.runIO
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log.Stdout.instance
import fs2.Stream
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps

class RedisStreamPublisherSubscriberSpec extends AnyFlatSpec with Matchers with PublisherSubscriberSpec {

  override def resource: Resource[IO, (Publisher[IO, TestMessage], Subscriber[IO, TestMessage])] =
    RedisContainer
      .create[IO]
      .flatMap { redisConfiguration =>
        for {
          publisher <- RedisStreamPublisher.create[IO, TestMessage](redisConfiguration)
          subscriber <- RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration)
        } yield (publisher: Publisher[IO, TestMessage], subscriber: Subscriber[IO, TestMessage])
      }

  "RedisStreamPublisher" should "cap the length of the stream" in runIO {
    RedisContainer
      .create[IO]
      .flatMap { redisConfiguration =>
        RedisStreamPublisher
          .create[IO, TestMessage](redisConfiguration, maxLength = 100)
          .product(Redis[IO].utf8(redisConfiguration.uri))
      }
      .use {
        case (publisher, redisCommands) =>
          for {
            _ <- Stream
              .range[IO, Int](0, 1000)
              .map(index => TestMessage(s"capped-$index", index))
              .through(publisher.publish)
              .compile
              .drain

            length <- redisCommands.xLen(testMessageMessagingTopic.name)

            // Approximate trimming removes whole macro nodes, so the stream can exceed the cap by a node or so
            _ <- IO.delay {
              length must be >= 100L
              length must be <= 300L
            }
          } yield ()
      }
  }

  "RedisStreamSubscriber" should "redeliver a message that was received but never committed" in runIO {
    RedisContainer
      .create[IO]
      .flatMap { redisConfiguration =>
        RedisStreamPublisher
          .create[IO, TestMessage](redisConfiguration)
          .product {
            RedisStreamSubscriber.create[IO, TestMessage](
              redisConfiguration,
              RedisStreamSubscriber.Settings(redeliveryTimeout = 1 second)
            )
          }
      }
      .use {
        case (publisher, subscriber) =>
          for {
            uncommittedFiber <- subscriber
              .subscribe("redelivery-group")
              .map(subscriber.extractValue)
              .take(1)
              .compile
              .toList
              .timeout(testTimeout)
              .start

            _ <- IO.sleep(2 seconds)
            _ <- publisher.publishOne(TestMessage("redelivered", 3))
            uncommitted <- uncommittedFiber.joinWithNever

            redelivered <- subscriber
              .subscribe("redelivery-group")
              .evalMap(record => subscriber.commit(List(record)).as(subscriber.extractValue(record)))
              .take(1)
              .compile
              .toList
              .timeout(testTimeout)

            _ <- IO.delay {
              uncommitted mustBe List(TestMessage("redelivered", 3))
              redelivered mustBe List(TestMessage("redelivered", 3))
            }
          } yield ()
      }
  }
}
