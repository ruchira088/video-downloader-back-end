package com.ruchij.api.daos.user.models

import com.ruchij.core.types.{EnumEntry, EnumValues}

enum Role extends EnumEntry {
  case User, Admin
}

object Role extends EnumValues[Role]
