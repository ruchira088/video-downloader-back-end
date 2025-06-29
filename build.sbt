import Dependencies._
import sbtrelease.Git
import sbtrelease.ReleaseStateTransformations._
import sbtrelease.Utilities.stateW

import java.awt.Desktop
import java.time.Instant
import scala.sys.process._
import scala.util.Try

val ReleaseBranch = "dev"
val ProductionBranch = "master"

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
        Seq[BuildInfoKey](
          name,
          organization,
          version,
          scalaVersion,
          sbtVersion,
          buildTimestamp,
          gitBranch,
          gitCommit
        ),
      buildInfoPackage := "com.eed3si9n.ruchij.migration",
      topLevelDirectory := None,
      libraryDependencies ++= Seq(catsEffect, flywayCore, flywayPostgresql, h2, postgresql, pureconfig, scalaLogging, logbackClassic),
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
          jsoup,
          scalaLogging,
          logbackClassic,
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
        Seq[BuildInfoKey](
          name,
          organization,
          version,
          scalaVersion,
          sbtVersion,
          buildTimestamp,
          gitBranch,
          gitCommit
        ),
      buildInfoPackage := "com.eed3si9n.ruchij.api",
      topLevelDirectory := None,
      Universal / javaOptions ++= Seq("-Dlogback.configurationFile=/opt/data/logback.xml"),
      libraryDependencies ++=
        Seq(http4sEmberServer, circeGeneric, circeParser, circeLiteral, postgresql, pureconfig, jbcrypt, logbackClassic) ++ Seq(pegdown).map(_ % Test)
    )
    .dependsOn(core % "compile->compile;test->test")

lazy val batch =
  (project in file("./batch"))
    .enablePlugins(BuildInfoPlugin, JavaAppPackaging)
    .settings(
      name := "video-downloader-batch",
      buildInfoKeys :=
        Seq[BuildInfoKey](
          name,
          organization,
          version,
          scalaVersion,
          sbtVersion,
          buildTimestamp,
          gitBranch,
          gitCommit
        ),
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

val verifyReleaseBranch = { state: State =>
  val git = Git.mkVcs(state.extract.get(baseDirectory))
  val branch = git.currentBranch

  if (branch != ReleaseBranch) {
    sys.error {
      s"The release branch is $ReleaseBranch, but the current branch is set to $branch"
    }
  } else state
}

val mergeReleaseToMaster = { state: State =>
  val git = Git.mkVcs(state.extract.get(baseDirectory))

  val (updatedState, releaseTag) = state.extract.runTask(releaseTagName, state)

  updatedState.log.info(s"Merging $releaseTag to $ProductionBranch...")

  val userInput: Option[ProcessBuilder] =
    SimpleReader
      .readLine("Push changes to the remote master branch (y/n)? [y]")
      .map(_.toUpperCase) match {
      case Some("Y") | Some("") =>
        updatedState.log.info(s"Pushing changes to remote master ($releaseTag)...")
        Some(git.cmd("push"))

      case _ =>
        updatedState.log.warn("Remember to push changes to remote master")
        None
    }

  val actions: List[ProcessBuilder] =
    List(git.cmd("checkout", ProductionBranch), git.cmd("pull", "--rebase"), git.cmd("merge", releaseTag)) ++
      userInput ++
      List(git.cmd("checkout", ReleaseBranch))

  actions.reduce(_ #&& _) !!

  updatedState.log.info(s"Successfully merged $releaseTag to $ProductionBranch")

  updatedState
}

releaseIgnoreUntrackedFiles := true

releaseProcess := Seq(
  ReleaseStep(verifyReleaseBranch),
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  ReleaseStep(mergeReleaseToMaster),
  setNextVersion,
  commitNextVersion,
  pushChanges
)

val viewCoverageResults = taskKey[Unit]("Opens the coverage result in the default browser")

viewCoverageResults := {
  val coverageResults =
    target.value.toPath.resolve(s"scala-${scalaBinaryVersion.value}/scoverage-report/index.html")

  Desktop.getDesktop.browse(coverageResults.toUri)
}

lazy val buildTimestamp = BuildInfoKey.action("buildTimestamp") { Instant.now() }
lazy val gitBranch = BuildInfoKey.action("gitBranch") { runGitCommand("git rev-parse --abbrev-ref HEAD") }
lazy val gitCommit =  BuildInfoKey.action("gitCommit") { runGitCommand("git rev-parse --short HEAD") }

def runGitCommand(command: String): Option[String] = {
  val gitFolder = new File(".git")

  if (gitFolder.exists()) Try(command !!).toOption.map(_.trim).filter(_.nonEmpty) else None
}

addCommandAlias("cleanCompile", "clean; compile;")
addCommandAlias("cleanTest", "clean; test;")
addCommandAlias("testWithCoverage", "clean; coverageOn; test; coverageAggregate; coverageOff; viewCoverageResults;")
