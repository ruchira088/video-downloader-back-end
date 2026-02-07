package com.ruchij.core.commands

import java.time.Instant

final case class ScanVideosCommand(issuedAt: Instant) extends Command
