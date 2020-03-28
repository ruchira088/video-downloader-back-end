package com.ruchij.config.models

import cats.{Applicative, MonadError}
import enumeratum.{Enum, EnumEntry}

sealed trait Database extends EnumEntry {
  protected val driverClass: Class[_]
  protected val name: String

  def driver: String = driverClass.getCanonicalName

  def test(url: String): Boolean = url.toLowerCase.startsWith(s"jdbc:$name")
}

object Database extends Enum[Database] {
  case object Postgres extends Database {
    override val driverClass: Class[_] = classOf[org.postgresql.Driver]

    override protected val name: String = "postgresql"
  }

  case object H2 extends Database {
    override val driverClass: Class[_] = classOf[org.h2.Driver]

    override protected val name: String = "h2"
  }

  override def values: IndexedSeq[Database] = findValues

  def inferDatabase[F[_]: MonadError[*[_], Throwable]](url: String): F[Database] =
    values
      .find(_.test(url))
      .fold[F[Database]](
        MonadError[F, Throwable]
          .raiseError(new IllegalArgumentException(s"""Unable to infer database driver from "$url""""))
      )(Applicative[F].pure)
}
