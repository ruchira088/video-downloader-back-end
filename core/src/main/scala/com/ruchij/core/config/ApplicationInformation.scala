package com.ruchij.core.config

import com.ruchij.core.config.models.ApplicationMode
import org.joda.time.DateTime

case class ApplicationInformation(
  mode: ApplicationMode,
  instanceId: String,
  gitBranch: Option[String],
  gitCommit: Option[String],
  buildTimestamp: Option[DateTime]
)
