package com.ruchij.batch.services.snapshots

import cats.effect.IO
import com.ruchij.batch.exceptions.SynchronizationException
import com.ruchij.core.services.cli.CliCommandRunner
import com.ruchij.core.services.hashing.HashingService
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.Providers
import com.ruchij.core.types.{Clock, RandomGenerator, TimeUtils}
import fs2.Stream
import org.http4s.MediaType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.util.UUID
import scala.concurrent.duration._

class VideoSnapshotServiceImplSpec extends AnyFlatSpec with Matchers {

  val timestamp = TimeUtils.instantOf(2024, 5, 15, 10, 30)
  val fixedUUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789012")

  class StubCliCommandRunner(result: Either[Throwable, String] = Right("")) extends CliCommandRunner[IO] {
    var executedCommands: List[String] = List.empty

    override def run(command: String): Stream[IO, String] = {
      executedCommands = executedCommands :+ command
      result match {
        case Right(output) => Stream.emit(output)
        case Left(error) => Stream.raiseError[IO](error)
      }
    }
  }

  class StubRepositoryService(
    sizeResult: Option[Long] = Some(1000L),
    fileTypeResult: Option[MediaType] = Some(MediaType.image.png)
  ) extends RepositoryService[IO] {
    override type BackedType = String
    override def write(key: String, data: Stream[IO, Byte]): Stream[IO, Nothing] = Stream.empty
    override def read(key: String, start: Option[Long], end: Option[Long]): IO[Option[Stream[IO, Byte]]] = IO.pure(None)
    override def size(key: String): IO[Option[Long]] = IO.pure(sizeResult)
    override def fileType(key: String): IO[Option[MediaType]] = IO.pure(fileTypeResult)
    override def delete(key: String): IO[Boolean] = IO.pure(true)
    override def list(prefix: String): Stream[IO, String] = Stream.empty
    override def exists(key: String): IO[Boolean] = IO.pure(sizeResult.isDefined)
    override def backedType(key: String): IO[String] = IO.pure(key)
  }

  class StubHashingService(hashResult: String = "abc123") extends HashingService[IO] {
    override def hash(input: String): IO[String] = IO.pure(hashResult)
  }

  "takeSnapshot" should "execute ffmpeg command and return FileResource" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = new RandomGenerator[IO, UUID] {
      override val generate: IO[UUID] = IO.pure(fixedUUID)
    }

    val cliCommandRunner = new StubCliCommandRunner()
    val repositoryService = new StubRepositoryService()
    val hashingService = new StubHashingService()

    val service = new VideoSnapshotServiceImpl[IO](cliCommandRunner, repositoryService, hashingService)

    service.takeSnapshot("/videos/test.mp4", 30.seconds, "/snapshots/snap.png").map { fileResource =>
      fileResource.path mustBe "/snapshots/snap.png"
      fileResource.mediaType mustBe MediaType.image.png
      fileResource.size mustBe 1000L
      fileResource.createdAt mustBe timestamp
      fileResource.id must include("abc123-snapshot-30000")

      cliCommandRunner.executedCommands must have length 1
      cliCommandRunner.executedCommands.head must include("ffmpeg")
      cliCommandRunner.executedCommands.head must include("-ss 30")
      cliCommandRunner.executedCommands.head must include("-i \"/videos/test.mp4\"")
      cliCommandRunner.executedCommands.head must include("\"/snapshots/snap.png\"")
    }
  }

  it should "handle different video timestamps" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = new RandomGenerator[IO, UUID] {
      override val generate: IO[UUID] = IO.pure(fixedUUID)
    }

    val cliCommandRunner = new StubCliCommandRunner()
    val repositoryService = new StubRepositoryService()
    val hashingService = new StubHashingService()

    val service = new VideoSnapshotServiceImpl[IO](cliCommandRunner, repositoryService, hashingService)

    for {
      _ <- service.takeSnapshot("/videos/test.mp4", 0.seconds, "/snapshots/snap1.png")
      _ <- service.takeSnapshot("/videos/test.mp4", 5.minutes, "/snapshots/snap2.png")
      _ <- service.takeSnapshot("/videos/test.mp4", 1.hour, "/snapshots/snap3.png")

      _ <- IO.delay {
        cliCommandRunner.executedCommands must have length 3
        cliCommandRunner.executedCommands(0) must include("-ss 0")
        cliCommandRunner.executedCommands(1) must include("-ss 300")
        cliCommandRunner.executedCommands(2) must include("-ss 3600")
      }
    } yield ()
  }

  it should "fail when snapshot file is not created (size is None)" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = new RandomGenerator[IO, UUID] {
      override val generate: IO[UUID] = IO.pure(fixedUUID)
    }

    val cliCommandRunner = new StubCliCommandRunner()
    val repositoryService = new StubRepositoryService(sizeResult = None)
    val hashingService = new StubHashingService()

    val service = new VideoSnapshotServiceImpl[IO](cliCommandRunner, repositoryService, hashingService)

    service.takeSnapshot("/videos/test.mp4", 30.seconds, "/snapshots/snap.png").attempt.map { result =>
      result.isLeft mustBe true
      result.left.toOption.get mustBe a[SynchronizationException]
      result.left.toOption.get.getMessage must include("unable to take snapshot")
    }
  }

  it should "fail when file type cannot be determined" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = new RandomGenerator[IO, UUID] {
      override val generate: IO[UUID] = IO.pure(fixedUUID)
    }

    val cliCommandRunner = new StubCliCommandRunner()
    val repositoryService = new StubRepositoryService(fileTypeResult = None)
    val hashingService = new StubHashingService()

    val service = new VideoSnapshotServiceImpl[IO](cliCommandRunner, repositoryService, hashingService)

    service.takeSnapshot("/videos/test.mp4", 30.seconds, "/snapshots/snap.png").attempt.map { result =>
      result.isLeft mustBe true
      result.left.toOption.get mustBe a[SynchronizationException]
    }
  }

  it should "fail when ffmpeg command fails" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = new RandomGenerator[IO, UUID] {
      override val generate: IO[UUID] = IO.pure(fixedUUID)
    }

    val cliCommandRunner = new StubCliCommandRunner(result = Left(new RuntimeException("ffmpeg error")))
    val repositoryService = new StubRepositoryService()
    val hashingService = new StubHashingService()

    val service = new VideoSnapshotServiceImpl[IO](cliCommandRunner, repositoryService, hashingService)

    service.takeSnapshot("/videos/test.mp4", 30.seconds, "/snapshots/snap.png").attempt.map { result =>
      result.isLeft mustBe true
      result.left.toOption.get.getMessage mustBe "ffmpeg error"
    }
  }

  it should "generate unique IDs for each snapshot" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)

    val uuids = List(
      UUID.fromString("aaaaa001-1234-1234-1234-123456789012"),
      UUID.fromString("bbbbb002-1234-1234-1234-123456789012")
    ).iterator
    implicit val randomGenerator: RandomGenerator[IO, UUID] = new RandomGenerator[IO, UUID] {
      override val generate: IO[UUID] = IO.delay(uuids.next())
    }

    val cliCommandRunner = new StubCliCommandRunner()
    val repositoryService = new StubRepositoryService()
    val hashingService = new StubHashingService()

    val service = new VideoSnapshotServiceImpl[IO](cliCommandRunner, repositoryService, hashingService)

    for {
      snap1 <- service.takeSnapshot("/videos/test.mp4", 30.seconds, "/snapshots/snap1.png")
      snap2 <- service.takeSnapshot("/videos/test.mp4", 30.seconds, "/snapshots/snap2.png")

      _ <- IO.delay {
        snap1.id must not equal snap2.id
      }
    } yield ()
  }

  it should "use correct media type for jpeg snapshots" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = new RandomGenerator[IO, UUID] {
      override val generate: IO[UUID] = IO.pure(fixedUUID)
    }

    val cliCommandRunner = new StubCliCommandRunner()
    val repositoryService = new StubRepositoryService(fileTypeResult = Some(MediaType.image.jpeg))
    val hashingService = new StubHashingService()

    val service = new VideoSnapshotServiceImpl[IO](cliCommandRunner, repositoryService, hashingService)

    service.takeSnapshot("/videos/test.mp4", 30.seconds, "/snapshots/snap.jpg").map { fileResource =>
      fileResource.mediaType mustBe MediaType.image.jpeg
    }
  }

  "VideoSnapshotService ffmpeg command" should "include correct parameters" in runIO {
    implicit val clock: Clock[IO] = Providers.stubClock[IO](timestamp)
    implicit val randomGenerator: RandomGenerator[IO, UUID] = new RandomGenerator[IO, UUID] {
      override val generate: IO[UUID] = IO.pure(fixedUUID)
    }

    val cliCommandRunner = new StubCliCommandRunner()
    val repositoryService = new StubRepositoryService()
    val hashingService = new StubHashingService()

    val service = new VideoSnapshotServiceImpl[IO](cliCommandRunner, repositoryService, hashingService)

    service.takeSnapshot("/videos/my video.mp4", 45.seconds, "/snapshots/output.png").map { _ =>
      val command = cliCommandRunner.executedCommands.head
      command must include("-frames:v 1")  // Take only 1 frame
      command must include("-q:v 2")        // Quality setting
      command must include("-y")            // Overwrite output
      command must include("-v error")      // Only show errors
    }
  }
}
