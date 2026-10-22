package com.ruchij.core.messaging.kafka

import cats.effect.{IO, Resource}
import cats.implicits._
import com.ruchij.core.config.KafkaConfiguration
import com.ruchij.core.external.containers.ContainerCoreResourcesProvider
import com.ruchij.core.messaging.PublisherSubscriberSpec.TestMessage._
import com.ruchij.core.messaging.PublisherSubscriberSpec.{PubSubFixture, SubscriptionReadiness, TestMessage}
import com.ruchij.core.messaging.PublisherSubscriberSpec
import fs2.kafka.{AdminClientSettings, KafkaAdminClient}
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.{GroupState, TopicPartition}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.language.postfixOps

class KafkaPublisherSubscriberSpec
    extends AnyFlatSpec
    with Matchers
    with PublisherSubscriberSpec {

  override def fixture: Resource[IO, PubSubFixture] =
    for {
      kafkaConfiguration <- new ContainerCoreResourcesProvider[IO].kafkaConfiguration
      pubSub <- KafkaPubSub[IO, TestMessage](kafkaConfiguration)
      adminClient <- KafkaAdminClient.resource[IO](AdminClientSettings(kafkaConfiguration.bootstrapServers))
    } yield PubSubFixture(pubSub, pubSub, new KafkaSubscriptionReadiness(adminClient, kafkaConfiguration, testTimeout))

  override def testTimeout: FiniteDuration = 30 seconds
}

/**
  * Makes the Kafka tests deterministic, where a fixed delay before publishing left two races:
  *
  *  - The subscriber starts from the latest offset, so a message published before a new consumer has looked up its
  *    position is skipped. Committing offset 0 for each group up front makes the consumers start from the beginning.
  *  - A topic created by the first publish, after the consumers joined, triggers a rebalance while they are consuming,
  *    and Kafka redelivers whatever was not yet committed. Creating the topic first and waiting for every group to
  *    settle with its partitions assigned means no rebalance happens once messages flow.
  */
final class KafkaSubscriptionReadiness(
  adminClient: KafkaAdminClient[IO],
  kafkaConfiguration: KafkaConfiguration,
  timeout: FiniteDuration
) extends SubscriptionReadiness {
  private val PollInterval: FiniteDuration = 100 milliseconds

  // A single admin request that stalls must not hold up the whole wait, so each one is abandoned and retried
  private val RequestTimeout: FiniteDuration = 10 seconds

  private val topicName: String = kafkaConfiguration.topicName(testMessageMessagingTopic.name)

  override def prepare(partitions: Int, groupIds: String*): IO[Unit] = {
    val startOffsets: Map[TopicPartition, OffsetAndMetadata] =
      (0 until partitions).map(partition => new TopicPartition(topicName, partition) -> new OffsetAndMetadata(0)).toMap

    retryUntilSuccessful(s"create the topic $topicName") {
      adminClient
        .createTopic(new NewTopic(topicName, partitions, 1.toShort))
        // An earlier attempt that timed out may have created it
        .recover { case error if causes(error).exists(_.isInstanceOf[TopicExistsException]) => () }
    } *>
      // The new topic's partitions may not be known to the group coordinator straight away
      retryUntilSuccessful(s"set the start offsets for ${groupIds.mkString(", ")}") {
        groupIds.toList.traverse_ { groupId =>
          adminClient.alterConsumerGroupOffsets(kafkaConfiguration.topicName(groupId), startOffsets)
        }
      }
  }

  override def awaitReady(subscribersByGroup: (String, Int)*): IO[Unit] = {
    val expected: Map[String, Int] =
      subscribersByGroup.map {
        case (groupId, subscribers) => kafkaConfiguration.topicName(groupId) -> subscribers
      }.toMap

    def unready: IO[List[String]] =
      for {
        partitions <- adminClient.describeTopics(List(topicName)).map(_(topicName).partitions().size)
        groups <- adminClient.describeConsumerGroups(expected.keys.toList)
      } yield expected.toList.flatMap {
        case (groupId, subscribers) =>
          val group = groups(groupId)
          val assignments =
            group.members().asScala.toList.map(_.assignment().topicPartitions().asScala.count(_.topic() == topicName))

          // Every partition is assigned, and to every subscriber when there are enough partitions to go round
          val ready =
            group.groupState() == GroupState.STABLE &&
              assignments.size == subscribers &&
              assignments.sum == partitions &&
              (subscribers > partitions || assignments.forall(_ > 0))

          if (ready) None
          else Some(s"$groupId: state=${group.groupState()}, partitions per subscriber=$assignments")
      }

    def poll(deadline: FiniteDuration): IO[Unit] =
      unready
        .timeout(RequestTimeout)
        .handleError(error => List(s"unable to describe the consumer groups: $error"))
        .flatMap { groups =>
          if (groups.isEmpty) IO.unit
          else
            IO.monotonic.flatMap { now =>
              if (now >= deadline)
                IO.raiseError {
                  new TimeoutException(s"Consumer groups not ready after $timeout: ${groups.mkString("; ")}")
                }
              else IO.sleep(PollInterval) *> poll(deadline)
            }
        }

    IO.monotonic.flatMap(now => poll(now + timeout))
  }

  private def causes(throwable: Throwable): List[Throwable] =
    Iterator.iterate(throwable)(_.getCause).takeWhile(_ != null).take(10).toList

  private def retryUntilSuccessful(description: String)(action: IO[Unit]): IO[Unit] = {
    def attempt(deadline: FiniteDuration): IO[Unit] =
      action.timeout(RequestTimeout).handleErrorWith { error =>
        IO.monotonic.flatMap { now =>
          if (now >= deadline)
            IO.raiseError(new TimeoutException(s"Unable to $description within $timeout: $error"))
          else IO.sleep(PollInterval) *> attempt(deadline)
        }
      }

    IO.monotonic.flatMap(now => attempt(now + timeout))
  }
}
