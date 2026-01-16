package com.ruchij.core.services.config

import cats.effect.IO
import cats.effect.kernel.Sync
import com.ruchij.core.daos.workers.models.VideoScan
import com.ruchij.core.daos.workers.models.VideoScan.ScanStatus.Scheduled
import com.ruchij.core.kv.keys.{KVStoreKey, KeySpace, KeySpacedKeyEncoder}
import com.ruchij.core.kv.{InMemoryKeyValueStore, KeySpacedKeyValueStore, KeyValueStore}
import com.ruchij.core.services.config.models.SharedConfigKey.{SharedConfigKeySpace, VideoScanningStatus}
import com.ruchij.core.services.config.models.{ConfigKey, SharedConfigKey}
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.JodaClock
import org.joda.time.DateTimeZone
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class ConfigurationServiceImplSpec extends AnyFlatSpec with Matchers {
  "ConfigurationServiceImpl" should "be able to configure shared config keys" in runIO {
    val configurationService = createConfigurationService[IO, SharedConfigKey](SharedConfigKeySpace)
    for {
      timestamp <- JodaClock[IO].timestamp.map(_.withZone(DateTimeZone.UTC))
      result <- configurationService.put(VideoScanningStatus, VideoScan(timestamp, Scheduled))
      _ <- IO.delay { result mustBe None }
      fetchedResult <- configurationService.get(VideoScanningStatus)
      _ <- IO.delay { fetchedResult mustBe Some(VideoScan(timestamp.withZone(DateTimeZone.UTC), Scheduled)) }
    } yield (): Unit
  }

  it should "return None when getting a non-existent key" in runIO {
    val configurationService = createConfigurationService[IO, SharedConfigKey](SharedConfigKeySpace)
    configurationService.get(VideoScanningStatus).map { result =>
      result mustBe None
    }
  }

  it should "update existing config values" in runIO {
    val configurationService = createConfigurationService[IO, SharedConfigKey](SharedConfigKeySpace)
    for {
      timestamp1 <- JodaClock[IO].timestamp.map(_.withZone(DateTimeZone.UTC))
      timestamp2 <- JodaClock[IO].timestamp.map(_.withZone(DateTimeZone.UTC).plusMinutes(1))

      _ <- configurationService.put(VideoScanningStatus, VideoScan(timestamp1, Scheduled))
      previousValue <- configurationService.put(VideoScanningStatus, VideoScan(timestamp2, VideoScan.ScanStatus.InProgress))

      _ <- IO.delay { previousValue mustBe Some(VideoScan(timestamp1.withZone(DateTimeZone.UTC), Scheduled)) }

      currentValue <- configurationService.get(VideoScanningStatus)
      _ <- IO.delay { currentValue mustBe Some(VideoScan(timestamp2.withZone(DateTimeZone.UTC), VideoScan.ScanStatus.InProgress)) }
    } yield (): Unit
  }

  it should "delete config values" in runIO {
    val configurationService = createConfigurationService[IO, SharedConfigKey](SharedConfigKeySpace)
    for {
      timestamp <- JodaClock[IO].timestamp.map(_.withZone(DateTimeZone.UTC))

      _ <- configurationService.put(VideoScanningStatus, VideoScan(timestamp, Scheduled))
      deletedValue <- configurationService.delete(VideoScanningStatus)

      _ <- IO.delay { deletedValue mustBe Some(VideoScan(timestamp.withZone(DateTimeZone.UTC), Scheduled)) }

      afterDelete <- configurationService.get(VideoScanningStatus)
      _ <- IO.delay { afterDelete mustBe None }
    } yield (): Unit
  }

  it should "return None when deleting non-existent key" in runIO {
    val configurationService = createConfigurationService[IO, SharedConfigKey](SharedConfigKeySpace)
    configurationService.delete(VideoScanningStatus).map { result =>
      result mustBe None
    }
  }

  def createConfigurationService[F[_]: Sync, K[_] <: ConfigKey with KVStoreKey](
    keySpace: KeySpace[K[_], String]
  )(implicit keyspaceKeyEncoder: KeySpacedKeyEncoder[F, K[_]]): ConfigurationServiceImpl[F, K] = {
    val keyValueStore: KeyValueStore[F] = new InMemoryKeyValueStore[F]
    val keySpacedKeyValueStore: KeySpacedKeyValueStore[F, K[_], String] = new KeySpacedKeyValueStore[F, K[_], String](keySpace, keyValueStore)

    new ConfigurationServiceImpl[F, K](keySpacedKeyValueStore)
  }

}
