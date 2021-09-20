package com.ruchij.api.daos.user.models

import enumeratum.{Enum, EnumEntry}

sealed trait Role extends EnumEntry

object Role extends Enum[Role] {
  case object UserRole extends Role
  case object AdminRole extends Role

  override def values: IndexedSeq[Role] = findValues
}
