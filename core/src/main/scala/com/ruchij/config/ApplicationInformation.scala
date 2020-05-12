package com.ruchij.config

import org.joda.time.DateTime

case class ApplicationInformation(
  gitBranch: Option[String],
  gitCommit: Option[String],
  buildTimestamp: Option[DateTime]
)
