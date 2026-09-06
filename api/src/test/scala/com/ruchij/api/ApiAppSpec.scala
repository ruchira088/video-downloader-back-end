package com.ruchij.api

import cats.effect.{IO, Resource}
import com.comcast.ip4s.IpLiteralSyntax
import com.ruchij.api.config.{ApiServiceConfiguration, AuthenticationConfiguration, HttpConfiguration}
import com.ruchij.api.test.matchers._
import com.ruchij.api.test.utils.JsonUtils
import com.ruchij.core.config._
import com.ruchij.core.external.CoreResourcesProvider
import com.ruchij.core.external.containers.RedisContainer
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.messaging.PubSub.PubsubType
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.migration.MigrationApp
import fs2.io.file.Files
import io.circe.literal._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.headers.Authorization
import org.http4s.implicits.http4sLiteralsSyntax
import org.http4s.{AuthScheme, Credentials, HttpApp, Method, Request, Status}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

/**
  * Boots the whole API against an in-memory database and a Redis container, then drives a few requests through the
  * real routes, services and DAOs. It guards the hand-written wiring in ApiApp.program.
  */
class ApiAppSpec extends AnyFlatSpec with Matchers {

  private val httpApp: Resource[IO, HttpApp[IO]] =
    for {
      redisConfiguration <- RedisContainer.create[IO]
      databaseConfiguration <- new EmbeddedCoreResourcesProvider[IO].databaseConfiguration
      _ <- Resource.eval {
        MigrationApp.migration[IO](CoreResourcesProvider.migrationServiceConfiguration(databaseConfiguration))
      }
      storageFolder <- Files[IO].tempDirectory

      apiServiceConfiguration = ApiServiceConfiguration(
        HttpConfiguration(ipv4"127.0.0.1", port"8000", None),
        StorageConfiguration(
          storageFolder.resolve("videos").toString,
          storageFolder.resolve("images").toString,
          List.empty
        ),
        databaseConfiguration,
        redisConfiguration,
        AuthenticationConfiguration(1.day),
        PubsubConfiguration(PubsubType.Doobie, None, None, Some(databaseConfiguration)),
        SpaSiteRendererConfiguration(uri"http://localhost:1"),
        SentryConfiguration(None, "test", 1.0),
        None
      )

      httpApp <- ApiApp.create[IO](apiServiceConfiguration)
    } yield httpApp

  private def bearer(secret: String) = Authorization(Credentials.Token(AuthScheme.Bearer, secret))

  private def login(httpApp: HttpApp[IO], email: String, password: String): IO[String] =
    httpApp
      .run(
        Request[IO](method = Method.POST, uri = uri"/authentication/login")
          .withEntity(json"""{"email": $email, "password": $password}""")
      )
      .flatMap { response =>
        IO.delay(response must haveStatus(Status.Created)).productR(JsonUtils.fromResponse(response))
      }
      .flatMap(json => IO.fromEither(json.hcursor.get[String]("secret")))

  "ApiApp.create" should "wire the application so users can register, authenticate and read worker status" in runIO {
    httpApp.use { app =>
      for {
        createResponse <- app.run(
          Request[IO](method = Method.POST, uri = uri"/users").withEntity(
            json"""{"firstName": "Smoke", "lastName": "Test", "email": "smoke.test@example.com", "password": "smoke-password"}"""
          )
        )
        _ <- IO.delay {
          createResponse must haveStatus(Status.Created)
          createResponse must beJsonContentType
        }

        userSecret <- login(app, "smoke.test@example.com", "smoke-password")

        userResponse <- app.run(
          Request[IO](method = Method.GET, uri = uri"/authentication/user").putHeaders(bearer(userSecret))
        )
        userJson <- JsonUtils.fromResponse(userResponse)
        _ <- IO.delay {
          userResponse must haveStatus(Status.Ok)
          userJson.hcursor.get[String]("email").toOption mustBe Some("smoke.test@example.com")
          userJson.hcursor.get[String]("role").toOption mustBe Some("User")
        }

        initialWorkerStatus <- app.run(
          Request[IO](method = Method.GET, uri = uri"/schedule/worker-status").putHeaders(bearer(userSecret))
        )
        _ <- IO.delay {
          initialWorkerStatus must haveStatus(Status.Ok)
          initialWorkerStatus must haveJson(json"""{"workerStatus": "Available"}""")
        }

        // The migration seeds the admin user; the default password is "top-secret"
        adminSecret <- login(app, "me@ruchij.com", "top-secret")

        pauseResponse <- app.run(
          Request[IO](method = Method.PUT, uri = uri"/schedule/worker-status")
            .putHeaders(bearer(adminSecret))
            .withEntity(json"""{"workerStatus": "Paused"}""")
        )
        pausedWorkerStatus <- app.run(
          Request[IO](method = Method.GET, uri = uri"/schedule/worker-status").putHeaders(bearer(userSecret))
        )
        _ <- IO.delay {
          pauseResponse must haveStatus(Status.Ok)
          pausedWorkerStatus must haveJson(json"""{"workerStatus": "Paused"}""")
        }

        unauthenticatedResponse <- app.run(Request[IO](method = Method.GET, uri = uri"/videos/search"))
        _ <- IO.delay(unauthenticatedResponse must haveStatus(Status.Unauthorized))
      } yield ()
    }
  }
}
