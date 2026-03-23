package com.ruchij.core.messaging.redis

import cats.effect.{IO, Resource}
import com.ruchij.core.external.containers.RedisContainer
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage._
import com.ruchij.core.messaging.{Publisher, PublisherSubscriberSpec, Subscriber}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

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
}
