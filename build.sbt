import Dependencies.*

import java.awt.Desktop
import java.time.Instant
import scala.sys.process.*
import scala.util.Try

// sbt 2: bare settings are common settings, injected into every subproject.
organization := "com.ruchij"
scalaVersion := Dependencies.ScalaVersion
maintainer := "me@ruchij.com"
scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Xfatal-warnings", "-Xlint")
resolvers ++= Seq("Confluent" at "https://packages.confluent.io/maven/", "jitpack" at "https://jitpack.io")
addCompilerPlugin(kindProjector)
addCompilerPlugin(betterMonadicFor)
// The report directory must be per project and absolute. A relative path resolves against each
// forked test JVM's working directory, so every module wrote into the same root `target/test-reports`
// and suites were silently dropped from the run -- `api/test` executed 14 of its 44 suites.
Test / testOptions += Tests.Argument(
  TestFrameworks.ScalaTest,
  "-u",
  ((Test / target).value / "test-reports").getAbsolutePath
)

Test / parallelExecution := true
Test / testForkedParallel := true

Global / concurrentRestrictions := Seq(
  Tags.limit(Tags.Test, 4),
  Tags.limit(Tags.Compile, 4)
)

Global / excludeLintKeys ++= Set(
  maintainer,
  Test / testForkedParallel,
  Debian / executableScriptName,
  Debian / sourceDirectory,
  Rpm / daemonStdoutLogFile,
  Rpm / executableScriptName,
  Rpm / name,
  Rpm / sourceDirectory,
  rpmScriptsDirectory,
  Universal / executableScriptName,
  UniversalDocs / name,
  UniversalSrc / name
)

lazy val migrationApplication =
  packagedApp("migrationApplication", "./migration-application", "com.eed3si9n.ruchij.migration")
    .settings(
      libraryDependencies ++=
        Seq(catsEffect, flywayCore, flywayPostgresql, h2, postgresql, pureconfig) ++ logging,
      libraryDependencies ++= Seq(scalaTest).map(_ % Test)
    )

lazy val core =
  (project in file("./core"))
    .settings(
      Test / fork := true,
      Test / parallelExecution := false,
      libraryDependencies ++=
        Seq(
          catsEffect,
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
          enumeratum,
          apacheTika,
          redis4CatsEffects,
          redis4catsStreams,
          jsoup,
          embeddedRedis,
          embeddedKafkaSchemaRegistry,
          testContainers,
          kafkaTestContainer,
          postgresqlTestContainer,
          redisTestContainer,
          sentry,
          perceptualHash
        ) ++ logging ++ circe ++
          Seq(scalaTest, scalaMock).map(_ % Test)
    )
    .dependsOn(migrationApplication)

lazy val api =
  packagedApp("api", "./api", "com.eed3si9n.ruchij.api")
    .settings(
      Test / fork := true,
      libraryDependencies ++=
        Seq(http4sEmberServer, postgresql, pureconfig, jbcrypt, logbackClassic) ++ circe ++
          Seq(circeLiteral, pegdown).map(_ % Test)
    )
    .dependsOn(core % "compile->compile;test->test")

lazy val batch =
  packagedApp("batch", "./batch", "com.eed3si9n.ruchij.batch")
    .settings(libraryDependencies ++= Seq(postgresql) ++ Seq(pegdown).map(_ % Test))
    .dependsOn(core % "compile->compile;test->test")

lazy val development =
  (project in file("./development"))
    .settings(name := "video-downloader-development")
    .dependsOn(migrationApplication, core, api, batch)


// scoverage ships `coverageOn` / `coverageOff` as aliases for `set ThisBuild / coverageEnabled := ...`.
// Under sbt 2 the `set` command fails to compile its synthetic wrapper against this multi-project
// build, so those aliases -- and therefore all coverage reporting -- are unusable. These commands
// apply the same session setting directly, without going through `set`.
def coverageToggle(commandName: String, enabled: Boolean): Command =
  Command.command(commandName) { state =>
    val extracted = Project.extract(state)
    extracted.appendWithSession(Seq(ThisBuild / coverageEnabled := enabled), state)
  }

commands ++= Seq(coverageToggle("coverageEnable", true), coverageToggle("coverageDisable", false))

val viewCoverageResults = taskKey[Unit]("Opens the coverage result in the default browser")

viewCoverageResults := {
  val coverageResults =
    target.value.toPath.resolve(s"scala-${scalaBinaryVersion.value}/scoverage-report/index.html")

  Desktop.getDesktop.browse(coverageResults.toUri)
}

lazy val buildTimestamp = BuildInfoKey.action("buildTimestamp") { Instant.now() }
lazy val gitBranch = BuildInfoKey.action("gitBranch") { runGitCommand("git rev-parse --abbrev-ref HEAD") }
lazy val gitCommit = BuildInfoKey.action("gitCommit") { runGitCommand("git rev-parse --short HEAD") }

def runGitCommand(command: String): Option[String] =
  Try(Process(command).!!).toOption.map(_.trim).filter(_.nonEmpty)

lazy val commonBuildInfoKeys =
  Seq[BuildInfoKey](name, organization, scalaVersion, sbtVersion, buildTimestamp, gitBranch, gitCommit)

lazy val logbackJavaOptions = Seq("-Dlogback.configurationFile=/opt/data/logback.xml")

def packagedApp(id: String, dir: String, buildInfoPackageName: String): Project =
  Project(id, file(dir))
    .enablePlugins(BuildInfoPlugin, JavaAppPackaging)
    .settings(
      name := s"video-downloader-${file(dir).getName}",
      buildInfoKeys := commonBuildInfoKeys,
      buildInfoPackage := buildInfoPackageName,
      topLevelDirectory := None,
      Universal / javaOptions ++= logbackJavaOptions
    )

addCommandAlias("cleanCompile", "clean; compile;")
addCommandAlias("cleanTest", "clean; test;")
addCommandAlias(
  "testWithCoverage",
  "clean; coverageEnable; test; coverageAggregate; coverageDisable; coverageReport; viewCoverageResults;"
)
