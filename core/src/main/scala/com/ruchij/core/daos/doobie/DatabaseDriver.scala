package com.ruchij.core.daos.doobie

import com.ruchij.core.types.{EnumEntry, EnumValues}
import org.{h2, postgresql}

enum DatabaseDriver(val driver: String) extends EnumEntry {
  case PostgreSQL extends DatabaseDriver(classOf[postgresql.Driver].getName)
  case H2 extends DatabaseDriver(classOf[h2.Driver].getName)
}

object DatabaseDriver extends EnumValues[DatabaseDriver] {
  def parseFromConnectionUrl(connectionUrl: String): Either[Throwable, DatabaseDriver] =
    DatabaseDriver.values
      .find { driver => connectionUrl.startsWith(s"jdbc:${driver.entryName.toLowerCase}") }
      .toRight {
        new IllegalArgumentException(
          s"""Unable to infer database driver from $connectionUrl. Supported protocols: [${DatabaseDriver.values
              .map(_.entryName)
              .mkString(", ")}]"""
        )
      }
}
