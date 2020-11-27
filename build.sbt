import Dependencies._
import sbtrelease.Git
import sbtrelease.ReleaseStateTransformations._
import sbtrelease.Utilities.stateW

import scala.sys.process.ProcessBuilder

val ReleaseBranch = "dev"
val ProductionBranch = "master"

inThisBuild {
  Seq(
    organization := "com.ruchij",
    scalaVersion := Dependencies.ScalaVersion,
    maintainer := "me@ruchij.com",
    scalacOptions ++= Seq("-feature", "-Xlint", "-Wconf:cat=lint-byname-implicit:s"),
    resolvers ++= Seq(
      "Confluent" at "https://packages.confluent.io/maven/",
      "jitpack" at "https://jitpack.io"
    ),
    addCompilerPlugin(kindProjector),
    addCompilerPlugin(betterMonadicFor),
    addCompilerPlugin(scalaTypedHoles)
  )
}

lazy val migrationApplication =
  (project in file("./migration-application"))
    .enablePlugins(JavaAppPackaging)
    .settings(
      name := "video-downloader-migration-application",
      topLevelDirectory := None,
      libraryDependencies ++= Seq(catsEffect, flywayCore, h2, postgresql, pureconfig, scalaLogging, logbackClassic)
    )

lazy val core =
  (project in file("./core"))
    .settings(
      libraryDependencies ++=
        Seq(
          catsEffect,
          http4sAsyncHttpClient,
          fs2Kafka,
          fs2KafkaVulkan,
          vulkanGeneric,
          h2,
          doobie,
          shapeless,
          pureconfig,
          jodaTime,
          enumeratum,
          apacheTika,
          redis4CatsEffects,
          jsoup,
          scalaLogging,
          logbackClassic
        ) ++ Seq(scalaTest, scalaMock).map(_ % Test)
    )
    .dependsOn(migrationApplication)

lazy val api =
  (project in file("./api"))
    .enablePlugins(BuildInfoPlugin, JavaAppPackaging)
    .settings(
      name := "video-downloader-api",
      buildInfoKeys := Seq[BuildInfoKey](name, organization, version, scalaVersion, sbtVersion),
      buildInfoPackage := "com.eed3si9n.ruchij.api",
      topLevelDirectory := None,
      libraryDependencies ++=
        Seq(
          http4sDsl,
          http4sBlazeServer,
          http4sCirce,
          circeGeneric,
          circeParser,
          circeLiteral,
          postgresql,
          pureconfig,
          jbcrypt,
          logbackClassic
        ) ++ Seq(scalaTest, pegdown).map(_ % Test)
    )
    .dependsOn(core % "compile->compile;test->test")

lazy val batch =
  (project in file("./batch"))
    .enablePlugins(JavaAppPackaging)
    .settings(
      name := "video-downloader-batch",
      topLevelDirectory := None,
      libraryDependencies ++= Seq(postgresql, jcodec, jcodecJavaSe, thumbnailator)
    )
    .dependsOn(core)

lazy val development =
  (project in file("./development"))
    .settings(
      name := "video-downloader-development",
      libraryDependencies ++= Seq(embeddedRedis, embeddedKafkaSchemaRegistry)
    )
    .dependsOn(migrationApplication, api, batch)

val compileAll = taskKey[Unit]("Compile all projects")
compileAll :=
  Def
    .sequential(
      migrationApplication / Compile / compile,
      core / Compile / compile,
      api / Compile / compile,
      batch / Compile / compile,
      development / Compile / compile
    )
    .value

val cleanAll = taskKey[Unit]("Clean all projects")
cleanAll := clean.all(ScopeFilter(inAnyProject)).value

val cleanCompile = taskKey[Unit]("Clean compile all projects")
cleanCompile := Def.sequential(cleanAll, compileAll).value

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
    SimpleReader.readLine("Push changes to the remote master branch (y/n)? [y] ")
      .map(_.toLowerCase) match {
        case Some("y") | Some("")  =>
          updatedState.log.info(s"Pushing changes to remote master ($releaseTag)...")
          Some(git.cmd("push"))

        case _ =>
          updatedState.log.warn("Remember to push changes to remote master")
          None
    }

  val actions: List[ProcessBuilder] =
    List(git.cmd("checkout", ProductionBranch), git.cmd("merge", releaseTag)) ++
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
  releaseStepTask(cleanCompile),
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  ReleaseStep(mergeReleaseToMaster),
  setNextVersion,
  commitNextVersion,
  pushChanges
)
