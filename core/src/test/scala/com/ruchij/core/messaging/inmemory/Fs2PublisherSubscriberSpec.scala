package com.ruchij.core.messaging.inmemory

import cats.effect.{IO, Resource}
import com.ruchij.core.messaging.PublisherSubscriberSpec.{PubSubFixture, SubscriptionReadiness, TestMessage}
import com.ruchij.core.messaging.PublisherSubscriberSpec
import fs2.concurrent.Topic
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps

class Fs2PublisherSubscriberSpec
    extends AnyFlatSpec
    with Matchers
    with PublisherSubscriberSpec {

  override def fixture: Resource[IO, PubSubFixture] =
    Resource.eval(Topic[IO, TestMessage]).map { topic =>
      val pubSub = new Fs2PubSub[IO, TestMessage](topic)

      val readiness =
        new SubscriptionReadiness {
          override def prepare(partitions: Int, groupIds: String*): IO[Unit] = IO.unit

          // The topic drops messages published before a subscription registers, so wait until they all have
          override def awaitReady(subscribersByGroup: (String, Int)*): IO[Unit] =
            topic.subscribers
              .find(_ >= subscribersByGroup.map { case (_, subscribers) => subscribers }.sum)
              .compile
              .drain
              .timeout(testTimeout)
        }

      PubSubFixture(pubSub, pubSub, readiness)
    }

  override def testTimeout: FiniteDuration = 10 seconds

  // The in-memory topic broadcasts every message to every subscriber
  override def supportsConsumerGroups: Boolean = false
}
