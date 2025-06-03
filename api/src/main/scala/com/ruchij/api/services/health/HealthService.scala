package com.ruchij.api.services.health

import com.ruchij.api.services.health.models.{HealthCheck, ServiceInformation}
import org.http4s.implicits.http4sLiteralsSyntax

trait HealthService[F[_]] {
  def serviceInformation: F[ServiceInformation]

  def healthCheck: F[HealthCheck]
}

object HealthService {
  val ConnectivityUrl = uri"https://ip.ruchij.workers.dev"
}
