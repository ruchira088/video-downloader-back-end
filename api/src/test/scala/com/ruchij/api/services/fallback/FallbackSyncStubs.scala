package com.ruchij.api.services.fallback

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.implicits._
import cats.~>
import com.ruchij.api.services.fallback.aws.FallbackSyncTransport
import com.ruchij.api.services.fallback.models.MainToFallbackMessage

object FallbackSyncStubs {
  implicit val identityTransaction: IO ~> IO = new (IO ~> IO) {
    override def apply[A](fa: IO[A]): IO[A] = fa
  }

  final class StubFallbackSyncDao(videos: Ref[IO, Map[String, SyncedVideo]]) extends FallbackSyncDao[IO] {
    override def findById(videoId: String): IO[Option[SyncedVideo]] = videos.get.map(_.get(videoId))

    override def findAll: IO[List[SyncedVideo]] = videos.get.map(_.values.toList)

    def remove(videoId: String): IO[Unit] = videos.update(_ - videoId)
  }

  object StubFallbackSyncDao {
    def apply(videos: SyncedVideo*): IO[StubFallbackSyncDao] =
      Ref
        .of[IO, Map[String, SyncedVideo]] {
          videos.map(video => video.scheduledVideoDownload.videoMetadata.id -> video).toMap
        }
        .map(new StubFallbackSyncDao(_))
  }

  final class RecordingTransport(sent: Ref[IO, List[MainToFallbackMessage]]) extends FallbackSyncTransport[IO] {
    override def send(messages: List[MainToFallbackMessage]): IO[Unit] = sent.update(_ ++ messages)

    val messages: IO[List[MainToFallbackMessage]] = sent.get
  }

  object RecordingTransport {
    def apply(): IO[RecordingTransport] = Ref.of[IO, List[MainToFallbackMessage]](Nil).map(new RecordingTransport(_))
  }

  /** Fails the first `failures` sends, then records. */
  final class FlakyTransport(remainingFailures: Ref[IO, Int], val recording: RecordingTransport)
      extends FallbackSyncTransport[IO] {
    override def send(messages: List[MainToFallbackMessage]): IO[Unit] =
      remainingFailures.modify(n => (n - 1, n)).flatMap { n =>
        if (n > 0) IO.raiseError(new RuntimeException("SQS unavailable")) else recording.send(messages)
      }
  }

  object FlakyTransport {
    def apply(failures: Int): IO[FlakyTransport] =
      (Ref.of[IO, Int](failures), RecordingTransport()).mapN(new FlakyTransport(_, _))
  }
}
