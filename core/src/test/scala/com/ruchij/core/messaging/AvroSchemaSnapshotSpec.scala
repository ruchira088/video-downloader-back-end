package com.ruchij.core.messaging

import org.apache.avro.{Schema, SchemaCompatibility, SchemaFormatter}
import org.scalatest.flatspec.FixtureAnyFlatSpec
import org.scalatest.fixture.ConfigMapFixture
import org.scalatest.matchers.must.Matchers
import org.scalatest.{Assertion, ConfigMap}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.util.Using

// Kafka messages are written with Avro schemas that vulcan-generic derives at compile time, and Schema Registry
// rejects a schema that is incompatible with the one it already holds for the topic. A new compiler (such as Scala 3)
// or a new vulcan version can change the derived schema without any change to the code, so each topic's schema is
// compared with a snapshot committed under <module>/src/test/resources/avro-schemas/<module>.
//
// Each module that defines MessagingTopic instances extends this spec with its topics. After an intended schema
// change, regenerate the module's snapshots and review the diff:
//   sbt "<module>/testOnly *AvroSchemaSnapshotSpec -- -Dupdate-avro-snapshots=true"
//
// The switch is a ScalaTest config map entry rather than an environment variable, because the sbt 2 thin client runs
// tests in a long-lived server, and forked tests inherit the environment of whichever command started that server.
abstract class AvroSchemaSnapshotSpec(module: String, topics: List[MessagingTopic[_]])
    extends FixtureAnyFlatSpec
    with ConfigMapFixture
    with Matchers {
  import AvroSchemaSnapshotSpec._

  private val updateCommand = s"""sbt "$module/testOnly *AvroSchemaSnapshotSpec -- -D$UpdateSnapshotsKey=true""""

  "topics" must "have distinct names, so that no two topics share a snapshot file" in { _ =>
    topics.map(_.name).distinct.size mustBe topics.size
  }

  topics.foreach { topic =>
    topic.name must "derive the Avro schema recorded in its snapshot" in { configMap =>
      val actual: Schema =
        topic.avroCodec.schema.fold(error => fail(s"Unable to derive the Avro schema: ${error.message}"), identity)

      if (updateSnapshots(configMap)) {
        writeSnapshot(module, topic.name, actual)
        succeed
      } else {
        val expected: Schema =
          readSnapshot(module, topic.name).getOrElse {
            fail(s"No Avro schema snapshot for ${topic.name}. Generate it with $updateCommand")
          }

        assertSchemaMatches(topic.name, expected, actual)
      }
    }
  }

  // A rolling deploy runs old and new replicas side by side, so new consumers read messages that old producers wrote,
  // and old consumers read messages that new producers wrote. A schema change must therefore be compatible in both
  // directions. A compatible change still fails, so that every schema change is reviewed through the snapshot diff.
  private def assertSchemaMatches(topicName: String, expected: Schema, actual: Schema): Assertion = {
    val incompatibilities =
      List(
        "New consumers reading old messages" -> (actual, expected),
        "Old consumers reading new messages" -> (expected, actual)
      )
        .flatMap { case (direction, (reader, writer)) =>
          SchemaCompatibility.checkReaderWriterCompatibility(reader, writer).getResult.getIncompatibilities.asScala
            .map(incompatibility =>
              s"$direction: ${incompatibility.getType} at ${incompatibility.getLocation}: ${incompatibility.getMessage}"
            )
        }

    if (incompatibilities.nonEmpty)
      fail(s"The Avro schema for $topicName is incompatible with its snapshot:\n${incompatibilities.mkString("\n")}")
    // Schema.equals ignores docs and aliases, so compare the schemas as they are written to the snapshot files
    else if (format(actual) != format(expected))
      fail(
        s"The Avro schema for $topicName changed compatibly. If the change is intended, regenerate the snapshot " +
          s"with $updateCommand and review the diff"
      )
    else succeed
  }
}

object AvroSchemaSnapshotSpec {
  private val UpdateSnapshotsKey = "update-avro-snapshots"

  // Forked tests run with their module's base directory as the working directory
  private def snapshotDirectory(module: String): Path = Paths.get("src", "test", "resources", "avro-schemas", module)

  private def updateSnapshots(configMap: ConfigMap): Boolean =
    configMap.getOptional[String](UpdateSnapshotsKey).exists(_.equalsIgnoreCase("true"))

  // A module that depends on core's test classpath also sees core's snapshots, so each module's snapshots live in a
  // directory named after it, and a missing snapshot cannot be masked by another module's topic of the same name
  private def readSnapshot(module: String, topicName: String): Option[Schema] =
    Option(getClass.getResourceAsStream(s"/avro-schemas/$module/$topicName.avsc")).map { inputStream =>
      Using.resource(Source.fromInputStream(inputStream, StandardCharsets.UTF_8.name())) { source =>
        new Schema.Parser().parse(source.mkString)
      }
    }

  private def format(schema: Schema): String = SchemaFormatter.format("json/pretty", schema)

  private def writeSnapshot(module: String, topicName: String, schema: Schema): Unit = {
    val directory = snapshotDirectory(module)
    Files.createDirectories(directory)
    Files.writeString(directory.resolve(s"$topicName.avsc"), format(schema) + "\n")
    ()
  }
}
