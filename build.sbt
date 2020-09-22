import Dependencies._

inThisBuild {
  Seq(
    organization := "com.ruchij",
    scalaVersion := SCALA_VERSION,
    maintainer := "me@ruchij.com",
    scalacOptions ++= Seq("-feature", "-Xlint", "-Wconf:cat=lint-byname-implicit:s"),
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
      version := "0.0.1",
      topLevelDirectory := None,
      libraryDependencies ++= Seq(catsEffect, flywayCore, h2, postgresql, pureconfig, scalaLogging, logbackClassic)
    )

lazy val core =
  (project in file("./core"))
    .settings(
      libraryDependencies ++=
        Seq(
          catsEffect,
          http4sBlazeClient,
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
      version := "0.0.1",
      buildInfoKeys := BuildInfoKey.ofN(name, organization, version, scalaVersion, sbtVersion),
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
          logbackClassic
        ) ++ Seq(scalaTest, pegdown).map(_ % Test)
    )
    .dependsOn(core % "compile->compile;test->test")

lazy val batch =
  (project in file("./batch"))
    .enablePlugins(JavaAppPackaging)
    .settings(
      name := "video-downloader-batch",
      version := "0.0.1",
      topLevelDirectory := None,
      libraryDependencies ++= Seq(postgresql, jcodec, jcodecJavaSe, thumbnailator)
    )
    .dependsOn(core)

val compileAll = taskKey[Unit]("Compile all projects")
compileAll :=
  Def
    .sequential(
      migrationApplication / Compile / compile,
      core / Compile / compile,
      api / Compile / compile,
      batch / Compile / compile
    )
    .value

val cleanAll = taskKey[Unit]("Clean all projects")
cleanAll := clean.all(ScopeFilter(inAnyProject)).value

val cleanCompile = taskKey[Unit]("Clean compile all projects")
cleanCompile := Def.sequential(cleanAll, compileAll).value
