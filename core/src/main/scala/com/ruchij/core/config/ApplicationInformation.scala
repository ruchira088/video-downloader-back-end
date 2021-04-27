package com.ruchij.core.config

import org.joda.time.DateTime

case class ApplicationInformation(
  instanceId: String,
  gitBranch: Option[String],
  gitCommit: Option[String],
  buildTimestamp: Option[DateTime]
)
