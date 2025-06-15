package com.ruchij.core.services.config.models

import com.ruchij.core.daos.workers.models.VideoScan
import com.ruchij.core.kv.keys.{KVStoreKey, KeySpace}
import enumeratum.EnumEntry

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps

sealed trait SharedConfigKey[A] extends EnumEntry with ConfigKey with KVStoreKey

object SharedConfigKey  {
  case object VideoScanningStatus extends SharedConfigKey[VideoScan] { self =>
    override val maybeTtl: Option[FiniteDuration] = Some(15 minutes)
    override val key: String = "video-scanning-status"
  }

  implicit case object SharedConfigKeySpace extends KeySpace[SharedConfigKey[_], String] {
    override val name: String = "shared-config"
  }
}
