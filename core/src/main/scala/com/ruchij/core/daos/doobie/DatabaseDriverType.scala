package com.ruchij.core.daos.doobie

import cats.{Applicative, ApplicativeError}
import enumeratum.{Enum, EnumEntry}
import org.{h2, postgresql}

import java.sql.Driver
import scala.reflect.ClassTag
import scala.util.matching.Regex

sealed abstract class DatabaseDriverType[A <: Driver](implicit classTag: ClassTag[A]) extends EnumEntry {
  val driver: String = classTag.runtimeClass.getName
}

object DatabaseDriverType extends Enum[DatabaseDriverType[_]] {
  case object PostgreSQL extends DatabaseDriverType[postgresql.Driver]
  case object H2 extends DatabaseDriverType[h2.Driver]

  val UrlName: Regex = "^jdbc:(\\w+):.*".r

  def parseFromConnectionUrl[F[_]: ApplicativeError[*[_], Throwable]](connectionUrl: String): F[DatabaseDriverType[_]] =
    connectionUrl match {
      case UrlName(name) =>
        DatabaseDriverType.values
          .find(_.entryName.equalsIgnoreCase(name))
          .map(Applicative[F].pure)
          .getOrElse {
            ApplicativeError[F, Throwable].raiseError {
              new IllegalArgumentException(
                s"""Extracted DB URL name "$name" does NOT match any DatabaseDriverTypes. Possible values: [${DatabaseDriverType.values
                  .map(_.entryName)
                  .mkString(", ")}]"""
              )
            }
          }

      case url =>
        ApplicativeError[F, Throwable].raiseError(
          new IllegalArgumentException(s"""Unable to infer driver from "$url"""")
        )
    }

  override def values: IndexedSeq[DatabaseDriverType[_]] = findValues
}
