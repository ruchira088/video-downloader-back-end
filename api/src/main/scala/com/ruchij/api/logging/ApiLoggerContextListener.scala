package com.ruchij.api.logging

import ch.qos.logback.classic.{Level, Logger, LoggerContext}
import ch.qos.logback.classic.spi.LoggerContextListener
import ch.qos.logback.core.spi.ContextAwareBase
import com.eed3si9n.ruchij.api.BuildInfo

class ApiLoggerContextListener extends ContextAwareBase with LoggerContextListener {

  override def isResetResistant: Boolean = true

  override def onStart(context: LoggerContext): Unit = {
    val hostname = sys.env.getOrElse("APP_HOSTNAME", "unknown")

    context.putProperty("app.name", BuildInfo.name)
    context.putProperty("git.commit", BuildInfo.gitCommit.getOrElse("unknown"))
    context.putProperty("git.branch", BuildInfo.gitBranch.getOrElse("unknown"))
    context.putProperty("app.hostname", hostname)
  }

  override def onReset(context: LoggerContext): Unit = {}

  override def onStop(context: LoggerContext): Unit = {}

  override def onLevelChange(logger: Logger, level: Level): Unit = {}
}
