package com.ruchij.core.services.repository

import cats.effect.IO
import com.ruchij.core.test.IOSupport.runIO
import fs2.io.file.Path
import org.apache.tika.Tika
import org.http4s.MediaType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.nio.file.Files

class PathFileTypeDetectorSpec extends AnyFlatSpec with Matchers {

  val tika = new Tika()
  val detector = new PathFileTypeDetector[IO](tika)

  "PathFileTypeDetector" should "detect text/plain for .txt files" in runIO {
    val tempFile = Files.createTempFile("test", ".txt")
    Files.write(tempFile, "Hello World".getBytes)

    detector.detect(Path.fromNioPath(tempFile)).flatMap { mediaType =>
      IO.delay(Files.deleteIfExists(tempFile)).map { _ =>
        mediaType mustBe MediaType.text.plain
      }
    }
  }

  it should "detect application/json for .json files" in runIO {
    val tempFile = Files.createTempFile("test", ".json")
    Files.write(tempFile, """{"key": "value"}""".getBytes)

    detector.detect(Path.fromNioPath(tempFile)).flatMap { mediaType =>
      IO.delay(Files.deleteIfExists(tempFile)).map { _ =>
        mediaType mustBe MediaType.application.json
      }
    }
  }

  it should "detect text/html for .html files" in runIO {
    val tempFile = Files.createTempFile("test", ".html")
    Files.write(tempFile, "<html><body>Hello</body></html>".getBytes)

    detector.detect(Path.fromNioPath(tempFile)).flatMap { mediaType =>
      IO.delay(Files.deleteIfExists(tempFile)).map { _ =>
        mediaType mustBe MediaType.text.html
      }
    }
  }

  it should "detect application/xml for .xml files" in runIO {
    val tempFile = Files.createTempFile("test", ".xml")
    Files.write(tempFile, """<?xml version="1.0"?><root></root>""".getBytes)

    detector.detect(Path.fromNioPath(tempFile)).flatMap { mediaType =>
      IO.delay(Files.deleteIfExists(tempFile)).map { _ =>
        mediaType mustBe MediaType.application.xml
      }
    }
  }

  it should "detect image/png for PNG image files" in runIO {
    // PNG file header bytes
    val pngHeader = Array[Byte](0x89.toByte, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    val tempFile = Files.createTempFile("test", ".png")
    Files.write(tempFile, pngHeader)

    detector.detect(Path.fromNioPath(tempFile)).flatMap { mediaType =>
      IO.delay(Files.deleteIfExists(tempFile)).map { _ =>
        mediaType mustBe MediaType.image.png
      }
    }
  }

  it should "detect image/jpeg for JPEG image files" in runIO {
    // JPEG file header bytes
    val jpegHeader = Array[Byte](0xFF.toByte, 0xD8.toByte, 0xFF.toByte, 0xE0.toByte)
    val tempFile = Files.createTempFile("test", ".jpg")
    Files.write(tempFile, jpegHeader)

    detector.detect(Path.fromNioPath(tempFile)).flatMap { mediaType =>
      IO.delay(Files.deleteIfExists(tempFile)).map { _ =>
        mediaType mustBe MediaType.image.jpeg
      }
    }
  }

  it should "detect video/mp4 for MP4 video files" in runIO {
    // MP4 file header bytes (simplified ftyp header)
    val mp4Header = Array[Byte](
      0x00, 0x00, 0x00, 0x1C, // size
      0x66, 0x74, 0x79, 0x70, // ftyp
      0x69, 0x73, 0x6F, 0x6D, // isom
      0x00, 0x00, 0x02, 0x00, // minor version
      0x69, 0x73, 0x6F, 0x6D, // isom
      0x69, 0x73, 0x6F, 0x32, // iso2
      0x6D, 0x70, 0x34, 0x31  // mp41
    )
    val tempFile = Files.createTempFile("test", ".mp4")
    Files.write(tempFile, mp4Header)

    detector.detect(Path.fromNioPath(tempFile)).flatMap { mediaType =>
      IO.delay(Files.deleteIfExists(tempFile)).map { _ =>
        mediaType mustBe MediaType.video.mp4
      }
    }
  }

  "Tika" should "detect file types correctly" in {
    val tika = new Tika()

    // Test text content detection
    tika.detect("Hello World".getBytes, "test.txt") mustBe "text/plain"

    // Test JSON content detection
    tika.detect("""{"key": "value"}""".getBytes, "test.json") mustBe "application/json"
  }
}
