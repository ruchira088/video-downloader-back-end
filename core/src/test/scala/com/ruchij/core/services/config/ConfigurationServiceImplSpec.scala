package com.ruchij.core.services.config

import cats.effect.IO
import cats.effect.kernel.Sync
import com.ruchij.core.daos.workers.models.VideoScan
import com.ruchij.core.daos.workers.models.VideoScan.ScanStatus.Scheduled
import com.ruchij.core.kv.keys.KeySpace
import com.ruchij.core.kv.{InMemoryKeyValueStore, KeySpacedKeyValueStore, KeyValueStore}
import com.ruchij.core.services.config.models.SharedConfigKey.{SharedConfigKeySpace, VideoScanningStatus}
import com.ruchij.core.services.config.models.SharedConfigKey
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.Clock
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class ConfigurationServiceImplSpec extends AnyFlatSpec with Matchers {
  "ConfigurationServiceImpl" should "be able to configure shared config keys" in runIO {
    val configurationService = createConfigurationService[IO](SharedConfigKeySpace)
    for {
      timestamp <- Clock[IO].timestamp
      result <- configurationService.put(VideoScanningStatus, VideoScan(timestamp, Scheduled))
      _ <- IO.delay { result mustBe None }
      fetchedResult <- configurationService.get(VideoScanningStatus)
      _ <- IO.delay { fetchedResult mustBe Some(VideoScan(timestamp, Scheduled)) }
    } yield (): Unit
  }

  it should "return None when getting a non-existent key" in runIO {
    val configurationService = createConfigurationService[IO](SharedConfigKeySpace)
    configurationService.get(VideoScanningStatus).map { result =>
      result mustBe None
    }
  }

  it should "update existing config values" in runIO {
    val configurationService = createConfigurationService[IO](SharedConfigKeySpace)
    for {
      timestamp1 <- Clock[IO].timestamp
      timestamp2 <- Clock[IO].timestamp.map(_.plus(java.time.Duration.ofMinutes(1)))

      _ <- configurationService.put(VideoScanningStatus, VideoScan(timestamp1, Scheduled))
      previousValue <- configurationService.put(VideoScanningStatus, VideoScan(timestamp2, VideoScan.ScanStatus.InProgress))

      _ <- IO.delay { previousValue mustBe Some(VideoScan(timestamp1, Scheduled)) }

      currentValue <- configurationService.get(VideoScanningStatus)
      _ <- IO.delay { currentValue mustBe Some(VideoScan(timestamp2, VideoScan.ScanStatus.InProgress)) }
    } yield (): Unit
  }

  it should "delete config values" in runIO {
    val configurationService = createConfigurationService[IO](SharedConfigKeySpace)
    for {
      timestamp <- Clock[IO].timestamp

      _ <- configurationService.put(VideoScanningStatus, VideoScan(timestamp, Scheduled))
      deletedValue <- configurationService.delete(VideoScanningStatus)

      _ <- IO.delay { deletedValue mustBe Some(VideoScan(timestamp, Scheduled)) }

      afterDelete <- configurationService.get(VideoScanningStatus)
      _ <- IO.delay { afterDelete mustBe None }
    } yield (): Unit
  }

  it should "return None when deleting non-existent key" in runIO {
    val configurationService = createConfigurationService[IO](SharedConfigKeySpace)
    configurationService.delete(VideoScanningStatus).map { result =>
      result mustBe None
    }
  }

  def createConfigurationService[F[_]: Sync](
    keySpace: KeySpace[SharedConfigKey[?], String]
  ): ConfigurationServiceImpl[F, SharedConfigKey, SharedConfigKey[?]] = {
    val keyValueStore: KeyValueStore[F] = new InMemoryKeyValueStore[F]

    new ConfigurationServiceImpl[F, SharedConfigKey, SharedConfigKey[?]](
      new KeySpacedKeyValueStore[F, SharedConfigKey[?], String](keySpace, keyValueStore)
    )
  }

}
