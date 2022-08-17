package com.ruchij.core.daos.doobie

import enumeratum.{Enum, EnumEntry}
import org.{h2, postgresql}

import java.sql
import scala.reflect.ClassTag

sealed abstract class DatabaseDriver[A <: sql.Driver](implicit classTag: ClassTag[A]) extends EnumEntry {
  val driver: String = classTag.runtimeClass.getName
}

object DatabaseDriver extends Enum[DatabaseDriver[_]] {
  case object PostgreSQL extends DatabaseDriver[postgresql.Driver]
  case object H2 extends DatabaseDriver[h2.Driver]

  def parseFromConnectionUrl(connectionUrl: String): Either[Throwable, DatabaseDriver[_]] =
    DatabaseDriver.values
      .find { driver => connectionUrl.startsWith(s"jdbc:${driver.entryName.toLowerCase}") }
      .toRight {
        new IllegalArgumentException(
          s"""Unable to infer database driver from $connectionUrl. Supported protocols: [${DatabaseDriver.values.map(_.entryName).mkString(", ")}]"""
        )
      }

  override def values: IndexedSeq[DatabaseDriver[_]] = findValues
}
