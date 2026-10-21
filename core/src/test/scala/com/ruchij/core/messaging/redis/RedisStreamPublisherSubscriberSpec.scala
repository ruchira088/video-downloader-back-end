package com.ruchij.core.messaging.redis

import cats.effect.{IO, Resource}
import cats.implicits._
import com.ruchij.core.external.containers.RedisContainer
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage._
import com.ruchij.core.messaging.{Publisher, PublisherSubscriberSpec, Subscriber}
import com.ruchij.core.test.IOSupport.runIO
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
