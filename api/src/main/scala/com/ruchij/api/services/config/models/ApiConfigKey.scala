package com.ruchij.api.services.config.models

import com.ruchij.core.daos.workers.models.WorkerStatus
import com.ruchij.core.kv.keys.{KVStoreKey, KeySpace}
import com.ruchij.core.services.config.models.ConfigKey

sealed trait ApiConfigKey[A] extends ConfigKey with KVStoreKey

object ApiConfigKey {
  case object WorkerStatus extends ApiConfigKey[WorkerStatus] { self =>
    override val key: String = "worker-status"
  }

  implicit case object ApiConfigKeySpace extends KeySpace[ApiConfigKey[_], String] {
    override val name: String = "api-config"
  }
}
