package com.ruchij.core.messaging.kafka

import cats.effect.IO
import com.ruchij.core.external.containers.ContainerCoreResourcesProvider
import com.ruchij.core.messaging.kafka.KafkaPubSubSpec.TestMessage
import com.ruchij.core.test.IOSupport.runIO
import fs2.Stream
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import vulcan.Codec
import vulcan.generic.MagnoliaCodec

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class KafkaPubSubSpec extends AnyFlatSpec with Matchers {

  "Kafka publisher and subscriber" should "be able to publish and subscribe to Kafka topic" in runIO {
    new ContainerCoreResourcesProvider[IO].kafkaConfiguration
      .flatMap {
        kafkaConfiguration => KafkaPubSub(kafkaConfiguration)(IO.asyncForIO, KafkaPubSubSpec.TestMessageTopic)
      }
      .use { pubSub =>
        pubSub.subscribe("test-subscriber").take(10).compile.toList.start
          .productL(IO.sleep(2 seconds))
          .productL {
            pubSub.publish { Stream.range[IO, Int](0, 10).map(index => TestMessage(index)) }
              .compile
              .drain
          }
          .flatMap(_.joinWithNever)
          .flatMap { committableRecords =>
            IO.delay {
              committableRecords.size mustBe 10
              committableRecords.map(_.value.index).toSet mustBe Range(0, 10).toSet
            }
          }
      }
  }

}

object KafkaPubSubSpec {
  final case class TestMessage(index: Int)

  implicit case object TestMessageTopic extends KafkaTopic[TestMessage] {
    override val name: String = "test-topic"

    override val codec: Codec[TestMessage] = Codec.derive[TestMessage]
  }
}