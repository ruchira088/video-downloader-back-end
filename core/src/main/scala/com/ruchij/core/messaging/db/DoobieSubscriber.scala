package com.ruchij.core.messaging.db

import cats.effect.kernel.{Async, Ref}
import cats.implicits._
import cats.{Applicative, Foldable, Functor, ~>}
import com.ruchij.core.daos.messaging.MessageDao
import com.ruchij.core.logging.Logger
import com.ruchij.core.messaging.Subscriber
import fs2.Stream
import io.circe.parser.parse

import scala.concurrent.duration._
import scala.language.postfixOps

class DoobieSubscriber[F[_]: Async, G[_], A](messageDao: MessageDao[G], pollInterval: FiniteDuration)(
  implicit doobieTopic: DoobieTopic[A],
  transaction: G ~> F
) extends Subscriber[F, A] {
  private val logger = Logger[DoobieSubscriber[F, G, A]]

  override type C[X] = X

  override def subscribe(groupId: String): Stream[F, A] = {
    val channel = doobieTopic.topicName

    Stream
      .eval(transaction(messageDao.maxId(channel)).flatMap(maxId => Ref.of[F, Long](maxId)))
      .flatMap { cursor =>
        Stream
          .fixedRate(pollInterval)
          .productR(Stream.eval(cursor.get))
          .flatMap { lastId =>
            Stream.eval(transaction(messageDao.findAfter(channel, lastId)))
          }
          .flatMap(rows => Stream.emits(rows))
          .evalMap {
            case (id, payload) => cursor.set(id).as(payload)
          }
          .flatMap { payload =>
            val decoded = for {
              json <- parse(payload)
              value <- doobieTopic.codec.decodeJson(json)
            } yield value

            decoded match {
              case Right(value) => Stream.emit(value)
              case Left(error) =>
                Stream
                  .eval(Async[F].delay(logger.error[F]("Unable to parse message", error)))
                  .productR(Stream.empty)
            }
          }
      }
  }

  override def commit[H[_]: Foldable: Functor](values: H[A]): F[Unit] =
    Applicative[F].unit

  override def extractValue(ca: A): A = ca
}

object DoobieSubscriber {
  val DefaultPollInterval: FiniteDuration = 3 seconds

  def create[F[_]: Async, G[_], A: DoobieTopic](
    messageDao: MessageDao[G],
    pollInterval: FiniteDuration = DefaultPollInterval
  )(implicit transaction: G ~> F): DoobieSubscriber[F, G, A] =
    new DoobieSubscriber[F, G, A](messageDao, pollInterval)
}
