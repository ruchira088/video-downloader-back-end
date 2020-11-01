package com.ruchij.core.services.scheduling.models

import com.ruchij.core.kv.keys.{KVStoreKey, KeySpace}
import org.joda.time.DateTime

import scala.concurrent.duration._
import scala.language.postfixOps

case class DownloadProgress(videoId: String, updatedAt: DateTime, bytes: Long)

object DownloadProgress {
  case class DownloadProgressKey(videoId: String) extends KVStoreKey

  implicit case object DownloadProgressKeySpace extends KeySpace[DownloadProgressKey, DownloadProgress] {
    override val name: String = "download-progress"

    override val ttl: FiniteDuration = 45 days
  }
}
