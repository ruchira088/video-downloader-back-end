import Dependencies.*

import java.awt.Desktop
import java.time.Instant
import scala.language.postfixOps
import scala.sys.process.*
import scala.util.Try

inThisBuild {
  Seq(
    organization := "com.ruchij",
    scalaVersion := Dependencies.ScalaVersion,
    maintainer := "me@ruchij.com",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xlint",
      "-Wconf:cat=lint-byname-implicit:s",
      "-Xlog-implicits"
    ),
    resolvers ++= Seq("Confluent" at "https://packages.confluent.io/maven/", "jitpack" at "https://jitpack.io"),
    addCompilerPlugin(kindProjector),
    addCompilerPlugin(betterMonadicFor)
  )
}

Global / excludeLintKeys ++= Set(maintainer)

lazy val migrationApplication =
  (project in file("./migration-application"))
    .enablePlugins(BuildInfoPlugin, JavaAppPackaging)
    .settings(
      name := "video-downloader-migration-application",
      buildInfoKeys :=
        Seq[BuildInfoKey](name, organization, scalaVersion, sbtVersion, buildTimestamp, gitBranch, gitCommit),
      buildInfoPackage := "com.eed3si9n.ruchij.migration",
      topLevelDirectory := None,
      Universal / javaOptions ++= Seq("-Dlogback.configurationFile=/opt/data/logback.xml"),
      libraryDependencies ++= Seq(
        catsEffect,
        flywayCore,
        flywayPostgresql,
        h2,
        postgresql,
        pureconfig,
        scalaLogging,
        logbackClassic,
        logstashLogbackEncoder
      ),
      libraryDependencies ++= Seq(scalaTest).map(_ % Test)
    )

lazy val core =
  (project in file("./core"))
    .settings(
      Test / fork := true,
      libraryDependencies ++=
        Seq(
          catsEffect,
          circeGeneric,
          circeParser,
          http4sJdkHttpClient,
          http4sDsl,
          http4sCirce,
          kafka,
          fs2Kafka,
          fs2KafkaVulkan,
          vulkanGeneric,
          kafkaAvroSerializer,
          h2,
          doobie,
          doobieHikari,
          shapeless,
          pureconfig,
          jodaTime,
          enumeratum,
          apacheTika,
          redis4CatsEffects,
          netty,
          jsoup,
          scalaLogging,
          logbackClassic,
          logstashLogbackEncoder,
          embeddedRedis,
          embeddedKafkaSchemaRegistry,
          testContainers,
          kafkaTestContainer,
          postgresqlTestContainer,
          redisTestContainer
        ) ++
          Seq(scalaTest, scalaMock).map(_ % Test)
    )
    .dependsOn(migrationApplication)

lazy val api =
  (project in file("./api"))
    .enablePlugins(BuildInfoPlugin, JavaAppPackaging)
    .settings(
      Test / fork := true,
      name := "video-downloader-api",
      buildInfoKeys :=
        Seq[BuildInfoKey](name, organization, scalaVersion, sbtVersion, buildTimestamp, gitBranch, gitCommit),
      buildInfoPackage := "com.eed3si9n.ruchij.api",
      topLevelDirectory := None,
      Universal / javaOptions ++= Seq("-Dlogback.configurationFile=/opt/data/logback.xml"),
      libraryDependencies ++=
        Seq(http4sEmberServer, circeGeneric, circeParser, circeLiteral, postgresql, pureconfig, jbcrypt, logbackClassic) ++ Seq(
          pegdown
        ).map(_ % Test)
    )
    .dependsOn(core % "compile->compile;test->test")

lazy val batch =
  (project in file("./batch"))
    .enablePlugins(BuildInfoPlugin, JavaAppPackaging)
    .settings(
      name := "video-downloader-batch",
      buildInfoKeys :=
        Seq[BuildInfoKey](name, organization, scalaVersion, sbtVersion, buildTimestamp, gitBranch, gitCommit),
      buildInfoPackage := "com.eed3si9n.ruchij.batch",
      topLevelDirectory := None,
      Universal / javaOptions ++= Seq("-Dlogback.configurationFile=/opt/data/logback.xml"),
      libraryDependencies ++=
        Seq(postgresql) ++ Seq(pegdown).map(_ % Test)
    )
    .dependsOn(core % "compile->compile;test->test")

lazy val development =
  (project in file("./development"))
    .settings(name := "video-downloader-development")
    .dependsOn(migrationApplication, core, api, batch)


val viewCoverageResults = taskKey[Unit]("Opens the coverage result in the default browser")

viewCoverageResults := {
  val coverageResults =
    target.value.toPath.resolve(s"scala-${scalaBinaryVersion.value}/scoverage-report/index.html")

  Desktop.getDesktop.browse(coverageResults.toUri)
}

lazy val buildTimestamp = BuildInfoKey.action("buildTimestamp") { Instant.now() }
lazy val gitBranch = BuildInfoKey.action("gitBranch") { runGitCommand("git rev-parse --abbrev-ref HEAD") }
lazy val gitCommit = BuildInfoKey.action("gitCommit") { runGitCommand("git rev-parse --short HEAD") }

def runGitCommand(command: String): Option[String] = {
  Try(command !!).toOption.map(_.trim).filter(_.nonEmpty)
}

addCommandAlias("cleanCompile", "clean; compile;")
addCommandAlias("cleanTest", "clean; test;")
addCommandAlias("testWithCoverage", "clean; coverageOn; test; coverageAggregate; coverageOff; viewCoverageResults;")
