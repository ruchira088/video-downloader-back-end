package com.ruchij.core.commands

import org.joda.time.DateTime

final case class ScanVideosCommand(issuedAt: DateTime) extends Command
