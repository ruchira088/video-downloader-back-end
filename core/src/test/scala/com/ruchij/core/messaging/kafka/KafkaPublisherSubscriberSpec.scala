package com.ruchij.core.messaging.kafka

import cats.effect.{IO, Resource}
import com.ruchij.core.external.containers.ContainerCoreResourcesProvider
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage._
import com.ruchij.core.messaging.{Publisher, PublisherSubscriberSpec, Subscriber}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps

class KafkaPublisherSubscriberSpec
    extends AnyFlatSpec
    with Matchers
    with PublisherSubscriberSpec {

  override def resource: Resource[IO, (Publisher[IO, TestMessage], Subscriber[IO, TestMessage])] =
    new ContainerCoreResourcesProvider[IO].kafkaConfiguration
      .flatMap { kafkaConfiguration =>
        KafkaPubSub[IO, TestMessage](kafkaConfiguration)
      }
      .map(pubSub => (pubSub, pubSub))

  override def testTimeout: FiniteDuration = 30 seconds
}
