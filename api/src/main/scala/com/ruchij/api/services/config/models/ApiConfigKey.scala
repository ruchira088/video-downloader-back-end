package com.ruchij.api.services.config.models

import cats.Applicative
import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.kv.keys.{KVStoreKey, KeySpace, KeySpacedKeyEncoder}
import com.ruchij.core.services.config.models.ConfigKey
import enumeratum.{Enum, EnumEntry}

import scala.concurrent.duration.FiniteDuration

sealed trait ApiConfigKey[A] extends ConfigKey with EnumEntry with KVStoreKey

object ApiConfigKey extends Enum[ApiConfigKey[_]] {
  case object WorkerStatus extends ApiConfigKey[WorkerStatus] {
    override val key: String = "worker-status"
  }

  override def values: IndexedSeq[ApiConfigKey[_]] = findValues

  case object ApiConfigKeySpace extends KeySpace[ApiConfigKey[_], String] {
    override val name: String = "api-config"
    override val maybeTtl: Option[FiniteDuration] = None
  }

  implicit def apiConfigKeySpacedKVEncoder[F[_]: Applicative]: KeySpacedKeyEncoder[F, ApiConfigKey[_]] =
    (apiConfigKey: ApiConfigKey[_]) => Applicative[F].pure(s"${ApiConfigKeySpace.name}-${apiConfigKey.key}")
}
