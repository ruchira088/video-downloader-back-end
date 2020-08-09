package com.ruchij.services.health.models

import enumeratum.{Enum, EnumEntry}

sealed trait HealthStatus extends EnumEntry {
  def +(healthStatus: HealthStatus): HealthStatus
}

object HealthStatus extends Enum[HealthStatus] {
  case object Healthy extends HealthStatus {
    override def +(healthStatus: HealthStatus): HealthStatus = healthStatus
  }

  case object Unhealthy extends HealthStatus {
    override def +(healthStatus: HealthStatus): HealthStatus = Unhealthy
  }

  override def values: IndexedSeq[HealthStatus] = findValues
}
