import sbt._

object Dependencies
{
  val SCALA_VERSION = "2.13.1"
  val HTTP4S_VERSION = "0.21.2"
  val CIRCE_VERSION = "0.13.0"

  lazy val http4sDsl = "org.http4s" %% "http4s-dsl" % HTTP4S_VERSION

  lazy val http4sBlazeServer = "org.http4s" %% "http4s-blaze-server" % HTTP4S_VERSION

  lazy val http4sCirce = "org.http4s" %% "http4s-circe" % HTTP4S_VERSION

  lazy val circeGeneric = "io.circe" %% "circe-generic" % CIRCE_VERSION

  lazy val circeParser = "io.circe" %% "circe-parser" % CIRCE_VERSION

  lazy val circeLiteral = "io.circe" %% "circe-literal" % CIRCE_VERSION

  lazy val jodaTime = "joda-time" % "joda-time" % "2.10.5"

  lazy val pureconfig = "com.github.pureconfig" %% "pureconfig" % "0.12.3"

  lazy val logbackClassic = "ch.qos.logback" % "logback-classic" % "1.2.3"

  lazy val kindProjector = "org.typelevel" %% "kind-projector" % "0.11.0" cross CrossVersion.full

  lazy val betterMonadicFor = "com.olegpy" %% "better-monadic-for" % "0.3.1"

  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.1.1"

  lazy val pegdown = "org.pegdown" % "pegdown" % "1.6.0"
}
