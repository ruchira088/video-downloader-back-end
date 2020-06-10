import Dependencies._

inThisBuild {
  Seq(
    organization := "com.ruchij",
    version := "0.0.1",
    scalaVersion := SCALA_VERSION,
    maintainer := "me@ruchij.com",
    scalacOptions ++= Seq("-Xlint", "-feature"),
    addCompilerPlugin(kindProjector),
    addCompilerPlugin(betterMonadicFor),
    addCompilerPlugin(scalaTypedHoles)
  )
}

lazy val migrationApplication =
  (project in file("./migration-application"))
    .enablePlugins(JavaAppPackaging)
    .settings(
      name := "video-downloader-migration",
      libraryDependencies ++= Seq(catsEffect, flywayCore, h2, postgresql, pureconfig),
      topLevelDirectory := None
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
          pureconfig,
          jodaTime,
          enumeratum,
          jsoup,
          logbackClassic
        ) ++ Seq(scalaTest, scalaMock).map(_ % Test)
    )
    .dependsOn(migrationApplication)

lazy val web =
  (project in file("./web"))
    .enablePlugins(BuildInfoPlugin, JavaAppPackaging)
    .settings(
      name := "video-downloader-web",
      buildInfoKeys := BuildInfoKey.ofN(name, organization, version, scalaVersion, sbtVersion),
      buildInfoPackage := "com.eed3si9n.ruchij",
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
        topLevelDirectory := None,
        libraryDependencies ++= Seq(postgresql, jcodec, jcodecJavaSe)
      )
      .dependsOn(core)

addCommandAlias("compileAll", "; migrationApplication/compile; core/compile; web/compile; batch/compile")

addCommandAlias("cleanAll", "; batch/clean; web/clean; core/clean; migrationApplication/clean")

addCommandAlias("testAll", "; migrationApplication/test; core/test; web/test; batch/test")

addCommandAlias("refreshAll", "; cleanAll; compileAll")
