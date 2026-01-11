package com.ruchij.core.services.repository

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.ruchij.core.test.IOSupport.{IOWrapper, runIO}
import fs2.Stream
import fs2.io.file.{Files, Path}
import org.http4s.MediaType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.{BeforeAndAfterEach, OptionValues}

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

class FileRepositoryServiceSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach with OptionValues {

  private var tempDir: Path = _
  private var fileRepositoryService: FileRepositoryService[IO] = _
  private var stubFileTypeDetector: StubFileTypeDetector = _

  override def beforeEach(): Unit = {
    tempDir = Files[IO].createTempDirectory.unsafeRunSync()
    stubFileTypeDetector = new StubFileTypeDetector(MediaType.application.`octet-stream`)
    fileRepositoryService = new FileRepositoryService[IO](stubFileTypeDetector)
  }

  override def afterEach(): Unit = {
    Files[IO].deleteRecursively(tempDir).unsafeRunSync()
  }

  // ==================== write tests ====================

  "write" should "write data to a new file" in runIO {
    val filePath = tempDir.resolve("test-file.txt").toString
    val content = "Hello, World!"
    val dataStream = Stream.emits(content.getBytes(StandardCharsets.UTF_8))

    for {
      _ <- fileRepositoryService.write(filePath, dataStream).compile.drain
      result <- fileRepositoryService.read(filePath, None, None)
      bytes <- result.value.compile.toVector
    } yield {
      new String(bytes.toArray, StandardCharsets.UTF_8) mustBe content
    }
  }

  it should "append data to an existing file" in runIO {
    val filePath = tempDir.resolve("append-file.txt").toString
    val content1 = "Hello, "
    val content2 = "World!"
    val dataStream1 = Stream.emits(content1.getBytes(StandardCharsets.UTF_8))
    val dataStream2 = Stream.emits(content2.getBytes(StandardCharsets.UTF_8))

    for {
      _ <- fileRepositoryService.write(filePath, dataStream1).compile.drain
      _ <- fileRepositoryService.write(filePath, dataStream2).compile.drain
      result <- fileRepositoryService.read(filePath, None, None)
      bytes <- result.value.compile.toVector
    } yield {
      new String(bytes.toArray, StandardCharsets.UTF_8) mustBe "Hello, World!"
    }
  }

  it should "create parent directories if they do not exist when writing" in runIO {
    val nestedPath = tempDir.resolve("nested/deep/path/file.txt").toString
    val content = "nested content"
    val dataStream = Stream.emits(content.getBytes(StandardCharsets.UTF_8))

    for {
      parentPath <- IO.delay(Path(nestedPath).parent.get)
      _ <- Files[IO].createDirectories(parentPath)
      _ <- fileRepositoryService.write(nestedPath, dataStream).compile.drain
      result <- fileRepositoryService.read(nestedPath, None, None)
      bytes <- result.value.compile.toVector
    } yield {
      new String(bytes.toArray, StandardCharsets.UTF_8) mustBe content
    }
  }

  it should "handle writing empty data stream" in runIO {
    val filePath = tempDir.resolve("empty-file.txt").toString
    val emptyStream: Stream[IO, Byte] = Stream.empty

    for {
      _ <- fileRepositoryService.write(filePath, emptyStream).compile.drain
      exists <- fileRepositoryService.exists(filePath)
      size <- fileRepositoryService.size(filePath)
    } yield {
      exists mustBe true
      size.value mustBe 0L
    }
  }

  it should "handle writing large data" in runIO {
    val filePath = tempDir.resolve("large-file.bin").toString
    val largeData = Array.fill(1024 * 1024)(42.toByte) // 1MB of data
    val dataStream = Stream.emits(largeData)

    for {
      _ <- fileRepositoryService.write(filePath, dataStream).compile.drain
      size <- fileRepositoryService.size(filePath)
    } yield {
      size.value mustBe (1024 * 1024).toLong
    }
  }

  // ==================== read tests ====================

  "read" should "return Some with file content when file exists" in runIO {
    val filePath = tempDir.resolve("readable-file.txt").toString
    val content = "Readable content"

    for {
      _ <- Files[IO].writeUtf8(Path(filePath))(Stream.emit(content)).compile.drain
      result <- fileRepositoryService.read(filePath, None, None)
      bytes <- result.value.compile.toVector
    } yield {
      new String(bytes.toArray, StandardCharsets.UTF_8) mustBe content
    }
  }

  it should "return None when file does not exist" in runIO {
    val nonExistentPath = tempDir.resolve("non-existent.txt").toString

    fileRepositoryService.read(nonExistentPath, None, None).map { result =>
      result mustBe None
    }
  }

  it should "read a range of bytes with start parameter" in runIO {
    val filePath = tempDir.resolve("range-file.txt").toString
    val content = "0123456789"

    for {
      _ <- Files[IO].writeUtf8(Path(filePath))(Stream.emit(content)).compile.drain
      result <- fileRepositoryService.read(filePath, Some(5), None)
      bytes <- result.value.compile.toVector
    } yield {
      new String(bytes.toArray, StandardCharsets.UTF_8) mustBe "56789"
    }
  }

  it should "read a range of bytes with start and end parameters" in runIO {
    val filePath = tempDir.resolve("range-file-2.txt").toString
    val content = "0123456789"

    for {
      _ <- Files[IO].writeUtf8(Path(filePath))(Stream.emit(content)).compile.drain
      result <- fileRepositoryService.read(filePath, Some(2), Some(7))
      bytes <- result.value.compile.toVector
    } yield {
      new String(bytes.toArray, StandardCharsets.UTF_8) mustBe "23456"
    }
  }

  it should "handle start offset of 0" in runIO {
    val filePath = tempDir.resolve("zero-offset-file.txt").toString
    val content = "test content"

    for {
      _ <- Files[IO].writeUtf8(Path(filePath))(Stream.emit(content)).compile.drain
      result <- fileRepositoryService.read(filePath, Some(0), None)
      bytes <- result.value.compile.toVector
    } yield {
      new String(bytes.toArray, StandardCharsets.UTF_8) mustBe content
    }
  }

  it should "handle reading from offset beyond file size" in runIO {
    val filePath = tempDir.resolve("beyond-eof-file.txt").toString
    val content = "short"

    for {
      _ <- Files[IO].writeUtf8(Path(filePath))(Stream.emit(content)).compile.drain
      result <- fileRepositoryService.read(filePath, Some(1000), None)
      bytes <- result.value.compile.toVector
    } yield {
      bytes mustBe empty
    }
  }

  // ==================== size tests ====================

  "size" should "return Some with file size when file exists" in runIO {
    val filePath = tempDir.resolve("size-file.txt").toString
    val content = "12345"

    for {
      _ <- Files[IO].writeUtf8(Path(filePath))(Stream.emit(content)).compile.drain
      result <- fileRepositoryService.size(filePath)
    } yield {
      result.value mustBe 5L
    }
  }

  it should "return None when file does not exist" in runIO {
    val nonExistentPath = tempDir.resolve("no-size-file.txt").toString

    fileRepositoryService.size(nonExistentPath).map { result =>
      result mustBe None
    }
  }

  it should "return 0 for empty file" in runIO {
    val filePath = tempDir.resolve("empty-size-file.txt").toString

    for {
      _ <- Files[IO].writeUtf8(Path(filePath))(Stream.empty).compile.drain
      result <- fileRepositoryService.size(filePath)
    } yield {
      result.value mustBe 0L
    }
  }

  it should "return correct size for large file" in runIO {
    val filePath = tempDir.resolve("large-size-file.bin").toString
    val expectedSize = 500000L
    val data = Array.fill(expectedSize.toInt)(0.toByte)

    for {
      _ <- Files[IO].writeAll(Path(filePath))(Stream.emits(data)).compile.drain
      result <- fileRepositoryService.size(filePath)
    } yield {
      result.value mustBe expectedSize
    }
  }

  // ==================== exists tests ====================

  "exists" should "return true when file exists" in runIO {
    val filePath = tempDir.resolve("existing-file.txt").toString

    for {
      _ <- Files[IO].writeUtf8(Path(filePath))(Stream.emit("content")).compile.drain
      result <- fileRepositoryService.exists(filePath)
    } yield {
      result mustBe true
    }
  }

  it should "return false when file does not exist" in runIO {
    val nonExistentPath = tempDir.resolve("non-existent-file.txt").toString

    fileRepositoryService.exists(nonExistentPath).map { result =>
      result mustBe false
    }
  }

  it should "return true for directory" in runIO {
    val dirPath = tempDir.resolve("test-dir")

    for {
      _ <- Files[IO].createDirectory(dirPath)
      result <- fileRepositoryService.exists(dirPath.toString)
    } yield {
      result mustBe true
    }
  }

  it should "return false for path with invalid characters" in runIO {
    IO.delay {
      tempDir.resolve("file\u0000name.txt").toString
    }.attempt.map { result =>
      result.isLeft mustBe true
      result.left.exists(_.isInstanceOf[java.nio.file.InvalidPathException]) mustBe true
    }
  }

  // ==================== list tests ====================

  "list" should "list all files in a directory" in runIO {
    val dir = tempDir.resolve("list-dir")

    for {
      _ <- Files[IO].createDirectory(dir)
      _ <- Files[IO].writeUtf8(dir.resolve("file1.txt"))(Stream.emit("content1")).compile.drain
      _ <- Files[IO].writeUtf8(dir.resolve("file2.txt"))(Stream.emit("content2")).compile.drain
      _ <- Files[IO].writeUtf8(dir.resolve("file3.txt"))(Stream.emit("content3")).compile.drain
      files <- fileRepositoryService.list(dir.toString).compile.toList.withTimeout(10 seconds)
    } yield {
      files.size mustBe 4 // includes the directory itself
      files.count(_.contains("file1.txt")) mustBe 1
      files.count(_.contains("file2.txt")) mustBe 1
      files.count(_.contains("file3.txt")) mustBe 1
    }
  }

  it should "list files in nested directories" in runIO {
    val dir = tempDir.resolve("nested-list-dir")
    val subDir = dir.resolve("subdir")

    for {
      _ <- Files[IO].createDirectories(subDir)
      _ <- Files[IO].writeUtf8(dir.resolve("root-file.txt"))(Stream.emit("root")).compile.drain
      _ <- Files[IO].writeUtf8(subDir.resolve("nested-file.txt"))(Stream.emit("nested")).compile.drain
      files <- fileRepositoryService.list(dir.toString).compile.toList.withTimeout(10 seconds)
    } yield {
      files.count(_.contains("root-file.txt")) mustBe 1
      files.count(_.contains("nested-file.txt")) mustBe 1
      files.count(_.contains("subdir")) mustBe 2 // directory and file inside
    }
  }

  it should "return only directory path for empty directory" in runIO {
    val emptyDir = tempDir.resolve("empty-dir")

    for {
      _ <- Files[IO].createDirectory(emptyDir)
      files <- fileRepositoryService.list(emptyDir.toString).compile.toList.withTimeout(10 seconds)
    } yield {
      files.size mustBe 1
      files.head must include("empty-dir")
    }
  }

  // ==================== delete tests ====================

  "delete" should "delete an existing file and return true" in runIO {
    val filePath = tempDir.resolve("delete-file.txt").toString

    for {
      _ <- Files[IO].writeUtf8(Path(filePath))(Stream.emit("to delete")).compile.drain
      existsBefore <- fileRepositoryService.exists(filePath)
      deleted <- fileRepositoryService.delete(filePath)
      existsAfter <- fileRepositoryService.exists(filePath)
    } yield {
      existsBefore mustBe true
      deleted mustBe true
      existsAfter mustBe false
    }
  }

  it should "return false when deleting a non-existent file" in runIO {
    val nonExistentPath = tempDir.resolve("no-delete-file.txt").toString

    fileRepositoryService.delete(nonExistentPath).map { result =>
      result mustBe false
    }
  }

  it should "delete an empty directory" in runIO {
    val dirPath = tempDir.resolve("delete-dir")

    for {
      _ <- Files[IO].createDirectory(dirPath)
      deleted <- fileRepositoryService.delete(dirPath.toString)
      exists <- fileRepositoryService.exists(dirPath.toString)
    } yield {
      deleted mustBe true
      exists mustBe false
    }
  }

  it should "fail to delete a non-empty directory" in runIO {
    val dirPath = tempDir.resolve("non-empty-dir")
    val filePath = dirPath.resolve("file.txt")

    for {
      _ <- Files[IO].createDirectory(dirPath)
      _ <- Files[IO].writeUtf8(filePath)(Stream.emit("content")).compile.drain
      result <- fileRepositoryService.delete(dirPath.toString).attempt
    } yield {
      result.isLeft mustBe true
      result.swap.toOption.value mustBe a[java.nio.file.DirectoryNotEmptyException]
    }
  }

  // ==================== fileType tests ====================

  "fileType" should "return Some with media type when file exists" in runIO {
    val filePath = tempDir.resolve("typed-file.txt").toString
    stubFileTypeDetector.setMediaType(MediaType.text.plain)

    for {
      _ <- Files[IO].writeUtf8(Path(filePath))(Stream.emit("text content")).compile.drain
      result <- fileRepositoryService.fileType(filePath)
    } yield {
      result.value mustBe MediaType.text.plain
    }
  }

  it should "return None when file does not exist" in runIO {
    val nonExistentPath = tempDir.resolve("no-type-file.txt").toString

    fileRepositoryService.fileType(nonExistentPath).map { result =>
      result mustBe None
    }
  }

  it should "detect different media types based on file type detector" in runIO {
    val videoPath = tempDir.resolve("video.mp4").toString
    stubFileTypeDetector.setMediaType(MediaType.video.mp4)

    for {
      _ <- Files[IO].writeUtf8(Path(videoPath))(Stream.emit("fake video content")).compile.drain
      result <- fileRepositoryService.fileType(videoPath)
    } yield {
      result.value mustBe MediaType.video.mp4
    }
  }

  // ==================== backedType tests ====================

  "backedType" should "parse a valid path string" in runIO {
    val pathString = "/tmp/some/path/file.txt"

    fileRepositoryService.backedType(pathString).map { path =>
      path.toString mustBe pathString
    }
  }

  it should "parse a relative path string" in runIO {
    val pathString = "relative/path/file.txt"

    fileRepositoryService.backedType(pathString).map { path =>
      path.toString mustBe pathString
    }
  }

  it should "handle paths with special characters" in runIO {
    val pathString = tempDir.resolve("file with spaces.txt").toString

    fileRepositoryService.backedType(pathString).map { path =>
      path.toString mustBe pathString
    }
  }

  it should "handle unicode paths" in runIO {
    val pathString = tempDir.resolve("unicode-path.txt").toString

    fileRepositoryService.backedType(pathString).map { path =>
      path.toString mustBe pathString
    }
  }

  // ==================== Integration tests ====================

  "FileRepositoryService" should "support full read-write-delete lifecycle" in runIO {
    val filePath = tempDir.resolve("lifecycle-file.txt").toString
    val content = "lifecycle content"
    val dataStream = Stream.emits(content.getBytes(StandardCharsets.UTF_8))

    for {
      existsInitial <- fileRepositoryService.exists(filePath)
      _ <- fileRepositoryService.write(filePath, dataStream).compile.drain
      existsAfterWrite <- fileRepositoryService.exists(filePath)
      readResult <- fileRepositoryService.read(filePath, None, None)
      bytes <- readResult.value.compile.toVector
      size <- fileRepositoryService.size(filePath)
      deleted <- fileRepositoryService.delete(filePath)
      existsAfterDelete <- fileRepositoryService.exists(filePath)
    } yield {
      existsInitial mustBe false
      existsAfterWrite mustBe true
      new String(bytes.toArray, StandardCharsets.UTF_8) mustBe content
      size.value mustBe content.length.toLong
      deleted mustBe true
      existsAfterDelete mustBe false
    }
  }

  it should "handle concurrent writes to different files" in runIO {
    import cats.implicits._

    val file1Path = tempDir.resolve("concurrent-1.txt").toString
    val file2Path = tempDir.resolve("concurrent-2.txt").toString
    val content1 = "content 1"
    val content2 = "content 2"

    val write1 = fileRepositoryService.write(file1Path, Stream.emits(content1.getBytes)).compile.drain
    val write2 = fileRepositoryService.write(file2Path, Stream.emits(content2.getBytes)).compile.drain

    for {
      _ <- (write1, write2).parTupled
      read1 <- fileRepositoryService.read(file1Path, None, None)
      read2 <- fileRepositoryService.read(file2Path, None, None)
      bytes1 <- read1.value.compile.toVector
      bytes2 <- read2.value.compile.toVector
    } yield {
      new String(bytes1.toArray) mustBe content1
      new String(bytes2.toArray) mustBe content2
    }
  }

  it should "handle binary data correctly" in runIO {
    val filePath = tempDir.resolve("binary-file.bin").toString
    val binaryData = (0 until 256).map(_.toByte).toArray
    val dataStream = Stream.emits(binaryData)

    for {
      _ <- fileRepositoryService.write(filePath, dataStream).compile.drain
      result <- fileRepositoryService.read(filePath, None, None)
      bytes <- result.value.compile.toVector
    } yield {
      bytes.toArray mustBe binaryData
    }
  }

  // ==================== Edge cases ====================

  "edge cases" should "handle reading immediately after writing" in runIO {
    val filePath = tempDir.resolve("immediate-read.txt").toString
    val content = "immediate"

    for {
      _ <- fileRepositoryService.write(filePath, Stream.emits(content.getBytes)).compile.drain
      result <- fileRepositoryService.read(filePath, None, None)
      bytes <- result.value.compile.toVector
    } yield {
      new String(bytes.toArray) mustBe content
    }
  }

  it should "handle multiple sequential appends" in runIO {
    val filePath = tempDir.resolve("multi-append.txt").toString

    for {
      _ <- fileRepositoryService.write(filePath, Stream.emits("a".getBytes)).compile.drain
      _ <- fileRepositoryService.write(filePath, Stream.emits("b".getBytes)).compile.drain
      _ <- fileRepositoryService.write(filePath, Stream.emits("c".getBytes)).compile.drain
      _ <- fileRepositoryService.write(filePath, Stream.emits("d".getBytes)).compile.drain
      result <- fileRepositoryService.read(filePath, None, None)
      bytes <- result.value.compile.toVector
    } yield {
      new String(bytes.toArray) mustBe "abcd"
    }
  }

  it should "handle paths with dots" in runIO {
    val filePath = tempDir.resolve("file.with.multiple.dots.txt").toString
    val content = "dotted"

    for {
      _ <- fileRepositoryService.write(filePath, Stream.emits(content.getBytes)).compile.drain
      exists <- fileRepositoryService.exists(filePath)
    } yield {
      exists mustBe true
    }
  }

  it should "handle hidden files (starting with dot)" in runIO {
    val filePath = tempDir.resolve(".hidden-file").toString
    val content = "hidden"

    for {
      _ <- fileRepositoryService.write(filePath, Stream.emits(content.getBytes)).compile.drain
      exists <- fileRepositoryService.exists(filePath)
      size <- fileRepositoryService.size(filePath)
    } yield {
      exists mustBe true
      size.value mustBe content.length.toLong
    }
  }
}

/**
 * A stub implementation of FileTypeDetector for testing purposes.
 * Allows setting the media type to be returned.
 */
class StubFileTypeDetector(private var mediaType: MediaType) extends FileTypeDetector[IO, Path] {

  def setMediaType(newMediaType: MediaType): Unit = {
    mediaType = newMediaType
  }

  override def detect(key: Path): IO[MediaType] = IO.pure(mediaType)
}
