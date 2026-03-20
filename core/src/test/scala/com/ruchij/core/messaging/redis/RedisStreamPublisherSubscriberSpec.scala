package com.ruchij.core.messaging.redis

import cats.Id
import cats.effect.{IO, Resource}
import com.ruchij.core.external.containers.RedisContainer
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage
import com.ruchij.core.messaging.{Publisher, PublisherSubscriberSpec, Subscriber}
import dev.profunktor.redis4cats.effect.Log.Stdout.instance
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class RedisStreamPublisherSubscriberSpec extends AnyFlatSpec with Matchers with PublisherSubscriberSpec[Id] {

  override def resource: Resource[IO, (Publisher[IO, TestMessage], Subscriber[IO, Id, TestMessage])] =
    RedisContainer
      .create[IO]
      .flatMap { redisConfiguration =>
        for {
          publisher <- RedisStreamPublisher.create[IO, TestMessage](redisConfiguration)
          subscriber <- RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration)
        } yield (publisher: Publisher[IO, TestMessage], subscriber: Subscriber[IO, Id, TestMessage])
      }

  override def extractValue(ga: Id[TestMessage]): TestMessage = ga
}
