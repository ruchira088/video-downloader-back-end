package com.ruchij.core.messaging.postgres

import cats.effect.kernel.{Async, Ref, Resource}
import cats.implicits._
import cats.{Applicative, Foldable, Functor, Id}
import com.ruchij.core.messaging.Subscriber
import com.typesafe.scalalogging.{Logger => ScalaLogger}
import com.ruchij.migration.config.DatabaseConfiguration
import doobie.Transactor
import doobie.implicits._
import fs2.Stream
import io.circe.parser.parse
import org.postgresql.PGConnection

import java.sql.DriverManager

class PostgresSubscriber[F[_]: Async, A](transactor: Transactor[F], listenConnection: java.sql.Connection)(
  implicit postgresTopic: PostgresTopic[A]
) extends Subscriber[F, Id, A] {
  private val logger = ScalaLogger[PostgresSubscriber[F, A]]
  private val PollTimeoutMs = 5000

  override def subscribe(groupId: String): Stream[F, A] = {
    val channel = postgresTopic.channelName

    Stream.eval {
      Async[F].blocking {
        listenConnection.createStatement().execute(s"LISTEN $channel")
      }
    }.drain ++
      Stream.eval {
        sql"SELECT COALESCE(MAX(id), 0) FROM message_queue WHERE channel = $channel"
          .query[Long]
          .unique
          .transact(transactor)
          .flatMap(maxId => Ref.of[F, Long](maxId))
      }.flatMap { cursor =>
        Stream.repeatEval {
          for {
            _ <- Async[F].blocking {
              listenConnection.unwrap(classOf[PGConnection]).getNotifications(PollTimeoutMs)
            }

            lastId <- cursor.get

            rows <- sql"SELECT id, payload FROM message_queue WHERE channel = $channel AND id > $lastId ORDER BY id ASC"
              .query[(Long, String)]
              .to[List]
              .transact(transactor)
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
  def create[F[_]: Async, A: PostgresTopic](
    databaseConfiguration: DatabaseConfiguration,
    transactor: Transactor[F]
  ): Resource[F, PostgresSubscriber[F, A]] =
    Resource.make(
      Async[F].blocking {
        DriverManager.getConnection(
          databaseConfiguration.url,
          databaseConfiguration.user,
          databaseConfiguration.password
        )
      }
    )(connection => Async[F].blocking(connection.close()))
      .map(connection => new PostgresSubscriber[F, A](transactor, connection))
}
