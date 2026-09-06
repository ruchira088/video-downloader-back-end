package com.ruchij.batch

import cats.effect.IO
import com.ruchij.batch.config.{BatchServiceConfiguration, WorkerConfiguration}
import com.ruchij.batch.daos.workers.DoobieWorkerDao
import com.ruchij.core.config._
import com.ruchij.core.daos.doobie.DoobieTransactor
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.external.CoreResourcesProvider
import com.ruchij.core.external.containers.RedisContainer
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.messaging.PubSub.PubsubType
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.migration.MigrationApp
import cats.effect.Resource
import fs2.io.file.Files
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.time.LocalTime
import scala.concurrent.duration._

/** Boots the batch worker against an in-memory database and a Redis container to guard the wiring in BatchApp.program. */
class BatchAppSpec extends AnyFlatSpec with Matchers {

  "BatchApp.program" should "initialise the workers and run the scheduler loop" in runIO {
    val resources =
      for {
        redisConfiguration <- RedisContainer.create[IO]
        databaseConfiguration <- new EmbeddedCoreResourcesProvider[IO].databaseConfiguration
        _ <- Resource.eval {
          MigrationApp.migration[IO](CoreResourcesProvider.migrationServiceConfiguration(databaseConfiguration))
        }
        storageFolder <- Files[IO].tempDirectory

        batchServiceConfiguration = BatchServiceConfiguration(
          StorageConfiguration(
            storageFolder.resolve("videos").toString,
            storageFolder.resolve("images").toString,
            List.empty
          ),
          WorkerConfiguration(2, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, "batch-app-spec"),
          databaseConfiguration,
          PubsubConfiguration(PubsubType.Doobie, None, None, Some(databaseConfiguration)),
          redisConfiguration,
          SpaSiteRendererConfiguration(uri"http://localhost:1"),
          SentryConfiguration(None, "test", 1.0),
          None
        )

        scheduler <- BatchApp.program[IO](batchServiceConfiguration)
        transactor <- DoobieTransactor.create[IO](databaseConfiguration)
      } yield (scheduler, transactor)

    resources.use {
      case (scheduler, transactor) =>
        for {
          _ <- scheduler.init
          // Let the polling loops run a few times to prove the wiring holds together
          _ <- scheduler.run.interruptAfter(3.seconds).compile.drain
          workers <- transactor.trans.apply(new DoobieWorkerDao(DoobieSchedulingDao).all)
        } yield {
          workers.map(_.id).sorted mustBe Seq("worker-00", "worker-01")
          workers.map(_.status).toSet mustBe Set(WorkerStatus.Available)
        }
    }
  }
}
