package com.ruchij.core.messaging.kafka

import cats.effect.{IO, Resource}
import com.ruchij.core.external.containers.ContainerCoreResourcesProvider
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage
import com.ruchij.core.messaging.{MessagingTopic, Publisher, PublisherSubscriberSpec, Subscriber}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import vulcan.Codec
import vulcan.generic.MagnoliaCodec

import scala.concurrent.duration._
import scala.language.postfixOps

class KafkaPublisherSubscriberSpec
    extends AnyFlatSpec
    with Matchers
    with PublisherSubscriberSpec {

  override def resource: Resource[IO, (Publisher[IO, TestMessage], Subscriber[IO, TestMessage])] =
    new ContainerCoreResourcesProvider[IO].kafkaConfiguration
      .flatMap { kafkaConfiguration =>
        KafkaPubSub[IO, TestMessage](kafkaConfiguration)(IO.asyncForIO, KafkaPublisherSubscriberSpec.TestMessageTopic)
      }
      .map(pubSub => (pubSub, pubSub))

  override def testTimeout: FiniteDuration = 30 seconds
}

object KafkaPublisherSubscriberSpec {
  implicit case object TestMessageTopic extends MessagingTopic[TestMessage] {
    override val name: String = "publisher-subscriber-test-topic"

    override val avroCodec: Codec[TestMessage] =
      Codec.derive[TestMessage]
  }
}
