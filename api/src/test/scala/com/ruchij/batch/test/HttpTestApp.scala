package com.ruchij.batch.test

import cats.effect.{Clock, Sync}
import com.ruchij.api.services.health.HealthServiceImpl
import com.ruchij.api.web.Routes
import org.http4s.HttpApp

object HttpTestApp {
  def apply[F[_]: Sync: Clock](): HttpApp[F] = ???
}
