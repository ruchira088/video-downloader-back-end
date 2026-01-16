package com.ruchij.migration.logging

import ch.qos.logback.classic.{Level, LoggerContext}
import com.eed3si9n.ruchij.migration.BuildInfo
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class MigrationLoggerContextListenerSpec extends AnyFlatSpec with Matchers {

  "MigrationLoggerContextListener" should "be reset resistant" in {
    val listener = new MigrationLoggerContextListener
    listener.isResetResistant mustBe true
  }

  it should "set app.name property on start" in {
    val listener = new MigrationLoggerContextListener
    val context = new LoggerContext

    listener.onStart(context)

    context.getProperty("app.name") mustBe BuildInfo.name
  }

  it should "set git.commit property on start" in {
    val listener = new MigrationLoggerContextListener
    val context = new LoggerContext

    listener.onStart(context)

    val expectedCommit = BuildInfo.gitCommit.getOrElse("unknown")
    context.getProperty("git.commit") mustBe expectedCommit
  }

  it should "set git.branch property on start" in {
    val listener = new MigrationLoggerContextListener
    val context = new LoggerContext

    listener.onStart(context)

    val expectedBranch = BuildInfo.gitBranch.getOrElse("unknown")
    context.getProperty("git.branch") mustBe expectedBranch
  }

  it should "set all properties correctly on start" in {
    val listener = new MigrationLoggerContextListener
    val context = new LoggerContext

    listener.onStart(context)

    context.getProperty("app.name") must not be null
    context.getProperty("git.commit") must not be null
    context.getProperty("git.branch") must not be null
  }

  "onReset" should "not throw an exception" in {
    val listener = new MigrationLoggerContextListener
    val context = new LoggerContext

    noException must be thrownBy listener.onReset(context)
  }

  "onStop" should "not throw an exception" in {
    val listener = new MigrationLoggerContextListener
    val context = new LoggerContext

    noException must be thrownBy listener.onStop(context)
  }

  "onLevelChange" should "not throw an exception" in {
    val listener = new MigrationLoggerContextListener
    val context = new LoggerContext
    val logger = context.getLogger("test.logger")

    noException must be thrownBy listener.onLevelChange(logger, Level.DEBUG)
  }

  it should "handle null level" in {
    val listener = new MigrationLoggerContextListener
    val context = new LoggerContext
    val logger = context.getLogger("test.logger")

    noException must be thrownBy listener.onLevelChange(logger, null)
  }

  "full lifecycle" should "handle start, level change, reset, and stop" in {
    val listener = new MigrationLoggerContextListener
    val context = new LoggerContext
    val logger = context.getLogger("test.logger")

    noException must be thrownBy {
      listener.onStart(context)
      listener.onLevelChange(logger, Level.INFO)
      listener.onReset(context)
      listener.onStop(context)
    }
  }

  it should "preserve properties after reset" in {
    val listener = new MigrationLoggerContextListener
    val context = new LoggerContext

    listener.onStart(context)
    val appNameBefore = context.getProperty("app.name")

    listener.onReset(context)

    // Properties should still be set (onReset doesn't clear them)
    context.getProperty("app.name") mustBe appNameBefore
  }
}
