package com.ruchij.core.messaging.postgres

import cats.effect.kernel.{Async, Ref, Resource}
import cats.implicits._
import cats.{Applicative, Foldable, Functor, Id, ~>}
import com.ruchij.core.daos.messaging.MessageDao
import com.ruchij.core.messaging.Subscriber
import com.ruchij.migration.config.DatabaseConfiguration
import com.typesafe.scalalogging.{Logger => ScalaLogger}
import fs2.Stream
import io.circe.parser.parse
import org.postgresql.PGConnection

import java.sql.DriverManager

class PostgresSubscriber[F[_]: Async, G[_], A](messageDao: MessageDao[G], listenConnection: java.sql.Connection)(
    implicit postgresTopic: PostgresTopic[A],
    transaction: G ~> F
) extends Subscriber[F, Id, A] {
  private val logger = ScalaLogger[PostgresSubscriber[F, G, A]]
  private val PollTimeoutMs = 5000

  override def subscribe(groupId: String): Stream[F, A] = {
    val channel = postgresTopic.channelName

    Stream.eval {
      Async[F].blocking {
        listenConnection.createStatement().execute(s"LISTEN $channel")
      }
    }.drain ++
      Stream.eval {
        transaction(messageDao.maxId(channel))
          .flatMap(maxId => Ref.of[F, Long](maxId))
      }.flatMap { cursor =>
        Stream.repeatEval {
          for {
            _ <- Async[F].blocking {
              listenConnection.unwrap(classOf[PGConnection]).getNotifications(PollTimeoutMs)
            }

            lastId <- cursor.get

            rows <- transaction(messageDao.findAfter(channel, lastId))
          } yield rows
        }
          .flatMap(rows => Stream.emits(rows))
          .evalMap { case (id, payload) =>
            cursor.set(id).as(payload)
          }
          .flatMap { payload =>
            val decoded = for {
              json <- parse(payload)
              value <- postgresTopic.codec.decodeJson(json)
            } yield value

            decoded match {
              case Right(value) => Stream.emit(value)
              case Left(error) =>
                Stream.eval(Async[F].delay(logger.error("Unable to parse message", error)))
                  .productR(Stream.empty)
            }
          }
      }
  }

  override def commit[H[_]: Foldable: Functor](values: H[A]): F[Unit] =
    Applicative[F].unit
}

object PostgresSubscriber {
  def create[F[_]: Async, G[_], A: PostgresTopic](
    messageDao: MessageDao[G],
    databaseConfiguration: DatabaseConfiguration
  )(implicit transaction: G ~> F): Resource[F, PostgresSubscriber[F, G, A]] =
    Resource.make(
      Async[F].blocking {
        DriverManager.getConnection(
          databaseConfiguration.url,
          databaseConfiguration.user,
          databaseConfiguration.password
        )
      }
    )(connection => Async[F].blocking(connection.close()))
      .map(connection => new PostgresSubscriber[F, G, A](messageDao, connection))
}
