package com.ruchij.kv.keys

import enumeratum.{Enum, EnumEntry}

sealed abstract class KeySpace(val name: String) extends EnumEntry

object KeySpace extends Enum[KeySpace] {
  def unapply(input: String): Option[KeySpace] = values.find(_.name.equalsIgnoreCase(input.trim))

  case object DownloadProgress extends KeySpace("download-progress")

  override def values: IndexedSeq[KeySpace] = findValues
}
