import Dependencies._

inThisBuild {
  Seq(
    organization := "com.ruchij",
    scalaVersion := SCALA_VERSION,
    maintainer := "me@ruchij.com",
    scalacOptions ++= Seq("-Xlint", "-feature"),
  )
}

lazy val migrationApplication =
  (project in file("./migration-application"))
    .settings(
      name := "migration-application",
      version := "0.0.1",
      libraryDependencies ++= Seq(catsEffect, flywayCore, h2, pureconfig)
    )

lazy val root =
  (project in file("."))
    .enablePlugins(BuildInfoPlugin, JavaAppPackaging)
    .settings(
      name := "video-downloader",
      version := "0.0.1",
      libraryDependencies ++= rootDependencies ++ rootTestDependencies.map(_ % Test),
      buildInfoKeys := BuildInfoKey.ofN(name, organization, version, scalaVersion, sbtVersion),
      buildInfoPackage := "com.eed3si9n.ruchij",
      topLevelDirectory := None,
      addCompilerPlugin(kindProjector),
      addCompilerPlugin(betterMonadicFor)
    )
  .dependsOn(migrationApplication)

lazy val rootDependencies =
  Seq(
    http4sDsl,
    http4sBlazeServer,
    http4sBlazeClient,
    http4sCirce,
    fs2Io,
    circeGeneric,
    circeParser,
    circeLiteral,
    jodaTime,
    enumeratum,
    doobiePostgres,
    jsoup,
    pureconfig,
    logbackClassic
  )

lazy val rootTestDependencies =
  Seq(scalaTest, pegdown)

addCommandAlias("testWithCoverage", "; coverage; test; coverageReport")
