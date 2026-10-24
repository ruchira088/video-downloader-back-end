package com.ruchij.core.messaging.redis

import cats.effect.{Deferred, IO, Resource}
import cats.implicits._
import com.ruchij.core.config.RedisConfiguration
import com.ruchij.core.external.containers.RedisContainer
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage._
import com.ruchij.core.messaging.PublisherSubscriberSpec.{PubSubFixture, SubscriptionReadiness, TestMessage}
import com.ruchij.core.messaging.PublisherSubscriberSpec
import com.ruchij.core.test.IOSupport.runIO
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.effect.Log.Stdout.instance
import dev.profunktor.redis4cats.effects.XGroupCreateArgs
import fs2.Stream
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.protocol.CommandType
import io.lettuce.core.{AclSetuserArgs, RedisClient}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps

class RedisStreamPublisherSubscriberSpec extends AnyFlatSpec with Matchers with PublisherSubscriberSpec {

  override def fixture: Resource[IO, PubSubFixture] =
    RedisContainer
      .create[IO]
      .flatMap { redisConfiguration =>
        for {
          publisher <- RedisStreamPublisher.create[IO, TestMessage](redisConfiguration)
          subscriber <- RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration)
          redisCommands <- Redis[IO].utf8(redisConfiguration.uri)

          readiness = new SubscriptionReadiness {
            // A new group starts from the latest message, so a message published before the first read created the
            // group would be skipped. Creating the groups up front makes them start before anything is published.
            override def prepare(partitions: Int, groupIds: String*): IO[Unit] =
              groupIds.toList.traverse_ { groupId =>
                redisCommands
                  .xGroupCreate(testMessageMessagingTopic.name, groupId, "$", XGroupCreateArgs(mkStream = true))
              }

            // Messages wait in the stream for the group, so a subscriber that has not read yet still receives them
            override def awaitReady(subscribersByGroup: (String, Int)*): IO[Unit] = IO.unit
          }
        } yield PubSubFixture(publisher, subscriber, readiness)
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
      .evalTap(createGroup(_, "redelivery-group"))
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

  it should "keep consuming after failing to acknowledge a message it cannot decode" in runIO {
    RedisContainer
      .create[IO]
      .evalTap(createGroup(_, "undecodable-group"))
      .flatMap { redisConfiguration =>
        (
          RedisStreamPublisher.create[IO, TestMessage](redisConfiguration),
          RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration),
          Redis[IO].utf8(redisConfiguration.uri),
          lettuce(redisConfiguration)
        ).tupled
      }
      .use {
        case (publisher, subscriber, redisCommands, lettuceCommands) =>
          def setXAck(allowed: Boolean): IO[Unit] =
            IO.blocking {
              lettuceCommands.aclSetuser(
                "default",
                if (allowed) AclSetuserArgs.Builder.addCommand(CommandType.XACK)
                else AclSetuserArgs.Builder.removeCommand(CommandType.XACK)
              )
            }.void

          for {
            receivedFiber <- subscriber
              .subscribe("undecodable-group")
              .map(subscriber.extractValue)
              .take(1)
              .compile
              .toList
              .timeout(testTimeout)
              .start

            // XACK fails with NOPERM until it is allowed again
            _ <- setXAck(allowed = false)
            _ <- redisCommands.xAdd(testMessageMessagingTopic.name, Map("data" -> "not-json"))
            _ <- publisher.publishOne(TestMessage("decodable", 1))
            _ <- IO.sleep(3 seconds)
            _ <- setXAck(allowed = true)

            received <- receivedFiber.joinWithNever

            _ <- IO.delay {
              received mustBe List(TestMessage("decodable", 1))
            }
          } yield ()
      }
  }

  it should "release the messages a subscription read but never acknowledged once it ends" in runIO {
    RedisContainer
      .create[IO]
      .evalTap(createGroup(_, "release-group"))
      .flatMap { redisConfiguration =>
        RedisStreamPublisher
          .create[IO, TestMessage](redisConfiguration)
          // Uses the default redelivery timeout of 5 minutes, which is far longer than the test timeout
          .product(RedisStreamSubscriber.create[IO, TestMessage](redisConfiguration))
      }
      .use {
        case (publisher, subscriber) =>
          for {
            firstFiber <- subscriber
              .subscribe("release-group")
              .map(subscriber.extractValue)
              .take(1)
              .compile
              .toList
              .timeout(testTimeout)
              .start

            _ <- publishAll(publisher, "release", 0 until 3)
            first <- firstFiber.joinWithNever

            second <- subscriber
              .subscribe("release-group")
              .evalMap(record => subscriber.commit(List(record)).as(subscriber.extractValue(record)))
              .take(3)
              .compile
              .toList
              .timeout(testTimeout)

            _ <- IO.delay {
              first.map(_.index) mustBe List(0)
              second.map(_.index) mustBe List(0, 1, 2)
            }
          } yield ()
      }
  }

  it should "delete consumers that no longer hold pending messages" in runIO {
    RedisContainer
      .create[IO]
      .evalTap(createGroup(_, "stale-group"))
      .flatMap { redisConfiguration =>
        (
          RedisStreamPublisher.create[IO, TestMessage](redisConfiguration),
          RedisStreamSubscriber.create[IO, TestMessage](
            redisConfiguration,
            RedisStreamSubscriber.Settings(redeliveryTimeout = 1 second)
          ),
          lettuce(redisConfiguration)
        ).tupled
      }
      .use {
        case (publisher, subscriber, lettuceCommands) =>
          val consumerCount: IO[Int] =
            IO.blocking(lettuceCommands.xinfoConsumers(testMessageMessagingTopic.name, "stale-group").size())

          for {
            // Ends while holding a message it never acknowledged, so its consumer is kept
            firstFiber <- subscriber
              .subscribe("stale-group")
              .map(subscriber.extractValue)
              .take(1)
              .compile
              .toList
              .timeout(testTimeout)
              .start

            _ <- publisher.publishOne(TestMessage("claimed-again", 1))
            _ <- firstFiber.joinWithNever

            acknowledged <- Deferred[IO, Unit]

            // Claims the message from the first consumer and acknowledges it
            consumerCounts <- subscriber
              .subscribe("stale-group")
              .evalMap(record => subscriber.commit(List(record)) *> acknowledged.complete(()).void)
              .compile
              .drain
              .background
              .use { _ =>
                // The first consumer may already be gone by the time the message is acknowledged, so only the
                // count it settles on is checked: the claiming consumer stays, the one that held the message doesn't
                acknowledged.get.timeout(testTimeout) *>
                  waitUntil(consumerCount.map(_ == 1)).timeout(15 seconds).attempt *>
                  consumerCount
              }

            _ <- IO.delay {
              consumerCounts mustBe 1
            }
          } yield ()
      }
  }

  /**
    * A new group starts from the latest message, so a message published before the subscriber's first read created
    * the group would be skipped. Creating the group up front lets a test publish straight away.
    */
  private def createGroup(redisConfiguration: RedisConfiguration, groupId: String): IO[Unit] =
    Redis[IO].utf8(redisConfiguration.uri).use { redisCommands =>
      redisCommands
        .xGroupCreate(testMessageMessagingTopic.name, groupId, "$", XGroupCreateArgs(mkStream = true))
        .void
    }

  private def lettuce(redisConfiguration: RedisConfiguration): Resource[IO, RedisCommands[String, String]] =
    Resource
      .make(IO.blocking(RedisClient.create(redisConfiguration.uri)))(client => IO.blocking(client.shutdown()))
      .flatMap(client => Resource.make(IO.blocking(client.connect()))(connection => IO.blocking(connection.close())))
      .map(_.sync())
}
