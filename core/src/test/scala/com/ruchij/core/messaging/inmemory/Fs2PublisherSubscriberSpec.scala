package com.ruchij.core.messaging.inmemory

import cats.Id
import cats.effect.{IO, Resource}
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage
import com.ruchij.core.messaging.models.CommittableRecord
import com.ruchij.core.messaging.{Publisher, PublisherSubscriberSpec, Subscriber}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps

class Fs2PublisherSubscriberSpec
    extends AnyFlatSpec
    with Matchers
    with PublisherSubscriberSpec[CommittableRecord[Id, *]] {

  override def resource
    : Resource[IO, (Publisher[IO, TestMessage], Subscriber[IO, CommittableRecord[Id, *], TestMessage])] =
    Resource.eval(Fs2PubSub[IO, TestMessage]).map(pubSub => (pubSub, pubSub))

  override def extractValue(ga: CommittableRecord[Id, TestMessage]): TestMessage = ga.value

  override def testTimeout: FiniteDuration = 10 seconds
}
