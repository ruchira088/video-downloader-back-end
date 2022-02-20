package com.ruchij.core.daos.doobie

import cats.{Applicative, ApplicativeError}
import enumeratum.{Enum, EnumEntry}
import org.{h2, postgresql}

import java.sql
import scala.reflect.ClassTag
import scala.util.matching.Regex

sealed abstract class DatabaseDriver[A <: sql.Driver](implicit classTag: ClassTag[A]) extends EnumEntry {
  val driver: String = classTag.runtimeClass.getName
}

object DatabaseDriver extends Enum[DatabaseDriver[_]] {
  case object PostgreSQL extends DatabaseDriver[postgresql.Driver]
  case object H2 extends DatabaseDriver[h2.Driver]

  val UrlName: Regex = "^jdbc:([^:]+):.*".r

  def parseFromConnectionUrl[F[_]: ApplicativeError[*[_], Throwable]](connectionUrl: String): F[DatabaseDriver[_]] =
    connectionUrl match {
      case UrlName(name) =>
        DatabaseDriver.values
          .find(_.entryName.equalsIgnoreCase(name))
          .map(Applicative[F].pure)
          .getOrElse {
            ApplicativeError[F, Throwable].raiseError {
              new IllegalArgumentException(
                s"""Extracted DB URL name "$name" does NOT match any DatabaseDriverTypes. Possible values: [${DatabaseDriver.values.map(_.entryName).mkString(", ")}]"""
              )
            }
          }

      case url =>
        ApplicativeError[F, Throwable].raiseError {
          new IllegalArgumentException(s"""Unable to infer driver from "$url"""")
        }
    }

  override def values: IndexedSeq[DatabaseDriver[_]] = findValues
}
