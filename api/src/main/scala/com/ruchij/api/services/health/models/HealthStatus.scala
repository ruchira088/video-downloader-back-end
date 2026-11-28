package com.ruchij.api.services.health.models

import com.ruchij.core.types.{EnumEntry, EnumValues}

enum HealthStatus extends EnumEntry {
  case Healthy, Unhealthy

  def +(healthStatus: HealthStatus): HealthStatus =
    this match {
      case Healthy => healthStatus
      case Unhealthy => Unhealthy
    }
}

object HealthStatus extends EnumValues[HealthStatus]
