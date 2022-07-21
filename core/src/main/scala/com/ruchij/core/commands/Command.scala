package com.ruchij.core.commands

import org.joda.time.DateTime

trait Command {
  val issuedAt: DateTime
}
