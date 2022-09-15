import Dependencies._
import sbtrelease.Git
import sbtrelease.ReleaseStateTransformations._
import sbtrelease.Utilities.stateW

import java.awt.Desktop
import scala.sys.process.ProcessBuilder

val ReleaseBranch = "dev"
val ProductionBranch = "master"

inThisBuild {
  Seq(
    organization := "com.ruchij",
    scalaVersion := Dependencies.ScalaVersion,
    maintainer := "me@ruchij.com",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Xlint", "-Wconf:cat=lint-byname-implicit:s"),
    resolvers ++= Seq("Confluent" at "https://packages.confluent.io/maven/", "jitpack" at "https://jitpack.io"),
    addCompilerPlugin(kindProjector),
    addCompilerPlugin(betterMonadicFor)
  )
}

lazy val migrationApplication =
  (project in file("./migration-application"))
    .enablePlugins(JavaAppPackaging)
    .settings(
      name := "video-downloader-migration-application",
      topLevelDirectory := None,
      libraryDependencies ++= Seq(catsEffect, flywayCore, h2, postgresql, pureconfig, scalaLogging, logbackClassic),
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
          postgresqlTestContainer
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
      buildInfoKeys := Seq[BuildInfoKey](name, organization, version, scalaVersion, sbtVersion),
      buildInfoPackage := "com.eed3si9n.ruchij.api",
      topLevelDirectory := None,
      Universal / javaOptions ++= Seq("-Dlogback.configurationFile=/opt/data/logback.xml"),
      libraryDependencies ++=
        Seq(http4sEmberServer, circeGeneric, circeParser, circeLiteral, postgresql, pureconfig, jbcrypt, logbackClassic)
          ++ Seq(pegdown).map(_ % Test)
    )
    .dependsOn(core % "compile->compile;test->test")

lazy val batch =
  (project in file("./batch"))
    .enablePlugins(JavaAppPackaging)
    .settings(
      name := "video-downloader-batch",
      topLevelDirectory := None,
      Universal / javaOptions ++= Seq("-Dlogback.configurationFile=/opt/data/logback.xml"),
      libraryDependencies ++=
        Seq(postgresql) ++ Seq(pegdown).map(_ % Test)
    )
    .dependsOn(core % "compile->compile;test->test")

lazy val fallbackApi =
  (project in file("./fallback-api"))
    .enablePlugins(BuildInfoPlugin, JavaAppPackaging)
    .settings(
      name := "video-downloader-fallback-api",
      topLevelDirectory := None,
      Universal / javaOptions ++= Seq("-Dlogback.configurationFile=/opt/data/logback.xml"),
      libraryDependencies ++= Seq(http4sEmberClient)
    )
    .dependsOn(api % "compile->compile;test->test")

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

releaseProcess := Seq(
  ReleaseStep(verifyReleaseBranch),
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
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

addCommandAlias("cleanCompile", "; clean; compile")
addCommandAlias("cleanTest", "; clean; test")
addCommandAlias("testWithCoverage", "; clean; coverage; test; coverageAggregate; viewCoverageResults")
