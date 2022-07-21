package com.ruchij.core.commands

import org.joda.time.DateTime

case class ScanVideosCommand(issuedAt: DateTime) extends Command
