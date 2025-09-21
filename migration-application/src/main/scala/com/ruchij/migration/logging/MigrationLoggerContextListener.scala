package com.ruchij.migration.logging

import ch.qos.logback.classic.{Level, Logger, LoggerContext}
import ch.qos.logback.classic.spi.LoggerContextListener
import ch.qos.logback.core.spi.ContextAwareBase
import com.eed3si9n.ruchij.migration.BuildInfo

class MigrationLoggerContextListener extends ContextAwareBase with LoggerContextListener {

  override def isResetResistant: Boolean = true

  override def onStart(context: LoggerContext): Unit = {
    context.putProperty("app.name", BuildInfo.name)
    context.putProperty("app.version", BuildInfo.version)
    context.putProperty("git.commit", BuildInfo.gitCommit.getOrElse("unknown"))
    context.putProperty("git.branch", BuildInfo.gitBranch.getOrElse("unknown"))
  }

  override def onReset(context: LoggerContext): Unit = {}

  override def onStop(context: LoggerContext): Unit = {}

  override def onLevelChange(logger: Logger, level: Level): Unit = {}
}
