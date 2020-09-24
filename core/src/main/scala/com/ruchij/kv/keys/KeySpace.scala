package com.ruchij.kv.keys

import com.ruchij.kv.keys.KVStoreKey.{DownloadProgressKey, HealthCheckKey}
import com.ruchij.services.scheduling.models.DownloadProgress
import enumeratum.{Enum, EnumEntry}
import org.joda.time.DateTime

sealed abstract class KeySpace[K <: KVStoreKey[K], V](val name: String) extends EnumEntry

object KeySpace extends Enum[KeySpace[_, _]] {
  def apply[A <: KVStoreKey[A]](implicit keySpace: KeySpace[A, _]): KeySpace[A, _] = keySpace

  def unapply(input: String): Option[KeySpace[_, _]] = values.find(_.name.equalsIgnoreCase(input.trim))

  implicit case object DownloadProgress extends KeySpace[DownloadProgressKey, DownloadProgress]("download-progress")

  implicit case object HealthCheck extends KeySpace[HealthCheckKey, DateTime]("health-check")

  override def values: IndexedSeq[KeySpace[_, _]] = findValues
}
