package com.ruchij.core.test

import org.scalatest.ConfigMap

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.io.Source
import scala.util.Using

// Snapshots committed under <module>/src/test/resources/<category>/<module>, which tests compare their output with.
//
// A module that depends on core's test classpath also sees core's snapshots, so each module's snapshots live in a
// directory named after it, and a missing snapshot cannot be masked by a snapshot of the same name in another module.
//
// Snapshots are regenerated with a ScalaTest config map entry, such as
//   sbt "<module>/testOnly <Spec> -- -D<update key>=true"
// rather than an environment variable, because the sbt 2 thin client runs tests in a long-lived server, and forked
// tests inherit the environment of whichever command started that server.
final case class SnapshotFiles(category: String, module: String, extension: String) {
  // Forked tests run with their module's base directory as the working directory
  private val directory: Path = Paths.get("src", "test", "resources", category, module)

  def read(name: String): Option[String] =
    Option(getClass.getResourceAsStream(s"/$category/$module/$name.$extension")).map { inputStream =>
      Using.resource(Source.fromInputStream(inputStream, StandardCharsets.UTF_8.name()))(_.mkString.stripSuffix("\n"))
    }

  def write(name: String, content: String): Unit = {
    Files.createDirectories(directory)
    Files.writeString(directory.resolve(s"$name.$extension"), content + "\n")
    ()
  }
}

object SnapshotFiles {
  def updateRequested(configMap: ConfigMap, updateKey: String): Boolean =
    configMap.getOptional[String](updateKey).exists(_.equalsIgnoreCase("true"))

  def updateCommand(module: String, specGlob: String, updateKey: String): String =
    s"""sbt "$module/testOnly $specGlob -- -D$updateKey=true""""
}
