package com.ruchij.core.daos.doobie

import enumeratum.{Enum, EnumEntry}

sealed trait DatabaseDriverType extends EnumEntry {
  val driver: String
  val urlName: String
}

object DatabaseDriverType extends Enum[DatabaseDriverType] {

  case object PostgreSQL extends DatabaseDriverType {
    override val driver: String = "org.postgresql.Driver"
    override val urlName: String = "postgresql"
  }

  case object H2 extends DatabaseDriverType {
    override val driver: String = "org.h2.Driver"
    override val urlName: String = "h2"
  }

  override def values: IndexedSeq[DatabaseDriverType] = findValues
}
