package com.ruchij

import cats.effect.{ExitCode, IO, IOApp}
import com.ruchij.config.ServiceConfiguration
import com.ruchij.services.health.HealthServiceImpl
import com.ruchij.web.Routes
import org.http4s.server.blaze.BlazeServerBuilder
import pureconfig.ConfigSource

object App extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    for {
      serviceConfiguration <- IO.suspend(IO.fromEither(ServiceConfiguration.parse(ConfigSource.default)))

      healthService = new HealthServiceImpl[IO]

      _ <-
        BlazeServerBuilder[IO]
          .withHttpApp(Routes(healthService))
          .bindHttp(serviceConfiguration.httpConfiguration.port, serviceConfiguration.httpConfiguration.host)
          .serve.compile.drain

    }
    yield ExitCode.Success
}
