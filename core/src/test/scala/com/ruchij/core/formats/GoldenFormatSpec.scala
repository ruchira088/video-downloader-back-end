package com.ruchij.core.formats

import com.ruchij.core.kv.codecs.{KVDecoder, KVEncoder}
import com.ruchij.core.kv.keys.{KVStoreKey, KeySpacedKeyEncoder}
import com.ruchij.core.messaging.MessagingTopic
import com.ruchij.core.test.SnapshotFiles
import org.scalatest.flatspec.FixtureAnyFlatSpec
import org.scalatest.fixture.ConfigMapFixture
import org.scalatest.matchers.must.Matchers

import scala.util.Try

// The JSON messages of the Redis and Doobie pub-sub backends, and the keys and values of the key-value store, outlive
// the release that wrote them: the Doobie backend keeps messages in PostgreSQL, and authentication tokens stay in the
// key-value store for weeks. Their encoders are derived at compile time, so a new compiler (such as Scala 3) or a
// library change can alter the format without any change to the code.
//
// Each format is therefore compared with a golden copy committed under <module>/src/test/resources/golden-formats/
// <module>. Encoding the value must produce the golden copy exactly, and decoding the golden copy, which stands for
// data written by an earlier release, must produce the value.
//
// After an intended format change, regenerate the module's golden copies and review the diff:
//   sbt "<module>/testOnly *GoldenFormatSpec -- -Dupdate-golden-formats=true"
abstract class GoldenFormatSpec(module: String, formats: List[GoldenFormat])
    extends FixtureAnyFlatSpec
    with ConfigMapFixture
    with Matchers {
  import GoldenFormatSpec._

  private val snapshotFiles = SnapshotFiles("golden-formats", module, "txt")

  private val updateCommand = SnapshotFiles.updateCommand(module, "*GoldenFormatSpec", UpdateKey)

  "formats" must "have distinct names, so that no two formats share a golden copy" in { _ =>
    formats.map(_.name).distinct.size mustBe formats.size
  }

  formats.foreach { format =>
    format.name must "encode to its golden copy, and decode the golden copy" in { configMap =>
      val encoded = format.encode.fold(throwable => fail(s"Unable to encode ${format.name}", throwable), identity)

      if (SnapshotFiles.updateRequested(configMap, UpdateKey)) {
        snapshotFiles.write(format.name, encoded)
        succeed
      } else {
        val golden =
          snapshotFiles
            .read(format.name)
            .getOrElse(fail(s"No golden copy for ${format.name}. Generate it with $updateCommand"))

        withClue(s"Decoding the golden copy of ${format.name}:") {
          format
            .decode(golden)
            .fold(throwable => fail(s"Unable to decode the golden copy of ${format.name}", throwable), identity)
            .foreach(_ mustBe format.value)
        }

        withClue(
          s"The encoding of ${format.name} changed. If the change is intended, regenerate the golden copy with " +
            s"$updateCommand and review the diff.\n"
        ) {
          encoded mustBe golden
        }
      }
    }
  }
}

object GoldenFormatSpec {
  private val UpdateKey = "update-golden-formats"
}

trait GoldenFormat {
  val name: String

  val value: Any

  def encode: Either[Throwable, String]

  // None for formats that are only ever written, such as key-value store keys
  def decode(golden: String): Either[Throwable, Option[Any]]
}

object GoldenFormat {
  def apply[A](formatName: String, formatValue: A)(
    encoder: A => Either[Throwable, String],
    decoder: Option[String => Either[Throwable, A]]
  ): GoldenFormat =
    new GoldenFormat {
      override val name: String = formatName

      override val value: Any = formatValue

      override def encode: Either[Throwable, String] = encoder(formatValue)

      override def decode(golden: String): Either[Throwable, Option[Any]] =
        decoder.fold[Either[Throwable, Option[Any]]](Right(None))(_(golden).map(Some.apply))
    }

  def json[A](value: A)(implicit messagingTopic: MessagingTopic[A]): GoldenFormat =
    GoldenFormat(s"json-${messagingTopic.name}", value)(
      value => Right(messagingTopic.jsonCodec(value).spaces2),
      Some(golden => io.circe.parser.decode(golden)(messagingTopic.jsonCodec))
    )

  // Keys are encoded with their key space's name in front, as KeySpacedKeyValueStore stores them
  def kvKey[K <: KVStoreKey](name: String, key: K)(implicit encoder: KeySpacedKeyEncoder[Try, K]): GoldenFormat =
    GoldenFormat(s"kv-key-$name", key)(key => encoder.encode(key).toEither, None)

  def kvValue[V](name: String, value: V)(
    implicit encoder: KVEncoder[Try, V],
    decoder: KVDecoder[Try, V]
  ): GoldenFormat =
    GoldenFormat(s"kv-value-$name", value)(value => encoder.encode(value).toEither, Some(decoder.decode(_).toEither))
}
