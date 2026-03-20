package com.ruchij.core.messaging.kafka

import cats.effect.{IO, Resource}
import com.ruchij.core.external.containers.ContainerCoreResourcesProvider
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage
import com.ruchij.core.messaging.models.CommittableRecord
import com.ruchij.core.messaging.{Publisher, PublisherSubscriberSpec, Subscriber}
import fs2.kafka.CommittableConsumerRecord
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import vulcan.Codec
import vulcan.generic.MagnoliaCodec

import scala.concurrent.duration._
import scala.language.postfixOps

class KafkaPublisherSubscriberSpec
    extends AnyFlatSpec
    with Matchers
    with PublisherSubscriberSpec[CommittableRecord[CommittableConsumerRecord[IO, Unit, *], *]] {

  override def resource: Resource[
    IO,
    (
      Publisher[IO, TestMessage],
      Subscriber[IO, CommittableRecord[CommittableConsumerRecord[IO, Unit, *], *], TestMessage]
    )
  ] =
    new ContainerCoreResourcesProvider[IO].kafkaConfiguration
      .flatMap { kafkaConfiguration =>
        KafkaPubSub[IO, TestMessage](kafkaConfiguration)(IO.asyncForIO, KafkaPublisherSubscriberSpec.TestMessageTopic)
      }
      .map(pubSub => (pubSub, pubSub))

  override def extractValue(ga: CommittableRecord[CommittableConsumerRecord[IO, Unit, *], TestMessage]): TestMessage =
    ga.value

  override def testTimeout: FiniteDuration = 30 seconds
}

object KafkaPublisherSubscriberSpec {
  implicit case object TestMessageTopic extends KafkaTopic[TestMessage] {
    override val name: String = "publisher-subscriber-test-topic"

    override val codec: Codec[TestMessage] =
      Codec.derive[TestMessage]
  }
}
