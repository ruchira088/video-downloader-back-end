package com.ruchij.core.messaging

import com.ruchij.core.test.SnapshotFiles
import org.apache.avro.{Schema, SchemaCompatibility, SchemaFormatter}
import org.scalatest.Assertion
import org.scalatest.flatspec.FixtureAnyFlatSpec
import org.scalatest.fixture.ConfigMapFixture
import org.scalatest.matchers.must.Matchers

import scala.jdk.CollectionConverters._

// Kafka messages are written with Avro schemas that vulcan-generic derives at compile time, and Schema Registry
// rejects a schema that is incompatible with the one it already holds for the topic. A new compiler (such as Scala 3)
// or a new vulcan version can change the derived schema without any change to the code, so each topic's schema is
// compared with a snapshot committed under <module>/src/test/resources/avro-schemas/<module>.
//
// Each module that defines MessagingTopic instances extends this spec with its topics. After an intended schema
// change, regenerate the module's snapshots and review the diff:
//   sbt "<module>/testOnly *AvroSchemaSnapshotSpec -- -Dupdate-avro-snapshots=true"
abstract class AvroSchemaSnapshotSpec(module: String, topics: List[MessagingTopic[?]])
    extends FixtureAnyFlatSpec
    with ConfigMapFixture
    with Matchers {
  import AvroSchemaSnapshotSpec._

  private val snapshotFiles = SnapshotFiles("avro-schemas", module, "avsc")

  private val updateCommand = SnapshotFiles.updateCommand(module, "*AvroSchemaSnapshotSpec", UpdateSnapshotsKey)

  "topics" must "have distinct names, so that no two topics share a snapshot file" in { _ =>
    topics.map(_.name).distinct.size mustBe topics.size
  }

  topics.foreach { topic =>
    topic.name must "derive the Avro schema recorded in its snapshot" in { configMap =>
      val actual: Schema =
        topic.avroCodec.schema.fold(error => fail(s"Unable to derive the Avro schema: ${error.message}"), identity)

      if (SnapshotFiles.updateRequested(configMap, UpdateSnapshotsKey)) {
        snapshotFiles.write(topic.name, format(actual))
        succeed
      } else {
        val expected: Schema =
          snapshotFiles
            .read(topic.name)
            .map(new Schema.Parser().parse(_))
            .getOrElse(fail(s"No Avro schema snapshot for ${topic.name}. Generate it with $updateCommand"))

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
          SchemaCompatibility
            .checkReaderWriterCompatibility(reader, writer)
            .getResult
            .getIncompatibilities
            .asScala
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

  private def format(schema: Schema): String = SchemaFormatter.format("json/pretty", schema)
}
