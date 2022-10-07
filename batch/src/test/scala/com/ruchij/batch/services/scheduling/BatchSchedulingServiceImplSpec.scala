package com.ruchij.batch.services.scheduling

import cats.Id
import cats.effect.IO
import cats.implicits._
import com.ruchij.batch.external.ExternalBatchServiceProvider
import com.ruchij.batch.external.containers.ContainerExternalBatchServiceProvider
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.messaging.models.CommittableRecord
import com.ruchij.core.messaging.{PubSub, Publisher, Subscriber}
import com.ruchij.core.services.scheduling.models.{DownloadProgress, WorkerStatusUpdate}
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.data.DataGenerators
import doobie.free.connection.ConnectionIO
import fs2.Stream
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global

class BatchSchedulingServiceImplSpec extends AnyFlatSpec with MockFactory with Matchers {

  "acquireTask" should "not return duplicate tasks" in runIO {
    val externalBatchServiceProvider: ExternalBatchServiceProvider[IO] =
      new ContainerExternalBatchServiceProvider[IO]

    val downloadProgressPublisher = mock[Publisher[IO, DownloadProgress]]
    val workerStatusSubscriber = mock[Subscriber[IO, CommittableRecord[Id, *], WorkerStatusUpdate]]
    val scheduledVideoDownloadPubSub = mock[PubSub[IO, CommittableRecord[Id, *], ScheduledVideoDownload]]

    val concurrency = 500
    val size = 1_000

    externalBatchServiceProvider.transactor.use { implicit transactor =>
      val batchSchedulingServiceImpl =
        new BatchSchedulingServiceImpl[IO, ConnectionIO, Id](
          downloadProgressPublisher,
          workerStatusSubscriber,
          scheduledVideoDownloadPubSub,
          DoobieSchedulingDao
        )

      Stream
        .eval(DataGenerators.scheduledVideoDownload[IO].generate)
        .repeat
        .take(size)
        .parEvalMapUnordered(concurrency) { scheduledVideoDownload =>
          transactor {
            DoobieFileResourceDao
              .insert(scheduledVideoDownload.videoMetadata.thumbnail)
              .productR(DoobieVideoMetadataDao.insert(scheduledVideoDownload.videoMetadata))
              .productR(DoobieSchedulingDao.insert(scheduledVideoDownload))
          }.as(scheduledVideoDownload)
        }
        .compile
        .toList
        .product {
          Stream.emit[IO, Unit]((): Unit)
            .repeat
            .parEvalMapUnordered(concurrency) { _ =>
              batchSchedulingServiceImpl.acquireTask.value
            }
            .collect { case Some(value) => value }
            .take(size)
            .compile
            .toList
        }
        .flatMap {
          case (persisted, retrieved) =>
            IO.delay {
              retrieved must have length persisted.size
              retrieved.map(_.videoMetadata.id) must contain allElementsOf persisted.map(_.videoMetadata.id)
              all(retrieved.map(_.status)) mustBe SchedulingStatus.Acquired
            }
        }
        .productR {
          Stream.emit[IO, Unit]((): Unit)
            .repeatN(concurrency)
            .evalMap { _ => batchSchedulingServiceImpl.acquireTask.value }
            .collect { case Some(value) => value }
            .compile
            .toList
            .flatMap { items =>
              IO.delay {
                items mustBe empty
              }
            }
        }
    }

  }

}
