package com.ruchij.core.commands

import java.time.Instant

trait Command {
  val issuedAt: Instant
}
