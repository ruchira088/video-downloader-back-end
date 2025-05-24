package com.ruchij.api.web.middleware

import cats.Applicative
import cats.data.Kleisli
import org.http4s.Method.{GET, OPTIONS, POST}
import org.http4s.Uri.Path
import org.http4s.Uri.Path.Root
import org.http4s.server.middleware.{CORS, CORSPolicy}
import org.http4s.{HttpApp, Method, Request, Response}

object Cors {
  private val OpenCorsPolicyRequests: Set[(Method, Path)] =
    Set(GET -> Root / "schedule" / "search", POST -> Root / "schedule", POST -> Root / "videos" / "metadata")

  private def hostRegex(host: String): Set[String] =
    Set(host)
      .flatMap(origin => if (origin.startsWith("*.")) Set(origin.substring(2), origin) else Set(origin))
      .map(origin => s"https?://${origin.replace(".", "\\.").replace("*", ".*")}(:\\d+)?$$")

  private def isOpenCorsPolicyRequest[F[_]](request: Request[F]): Boolean =
    OpenCorsPolicyRequests
      .concat(OpenCorsPolicyRequests.map { case (_, path) => OPTIONS -> path })
      .contains((request.method, request.pathInfo))

  def apply[F[_]: Applicative](allowedHosts: Set[String])(httpApp: HttpApp[F]): HttpApp[F] = {
    val hostsRegex = allowedHosts.flatMap(hostRegex).mkString("|").r

    val primaryCorsPolicy: CORSPolicy =
      CORS.policy
        .withAllowCredentials(true)
        .withAllowOriginHostCi { originHost =>
          hostsRegex.matches(originHost.toString)
        }

    val openCorsPolicy: CORSPolicy =
      CORS.policy
        .withAllowCredentials(true)
        .withAllowOriginHost { _ =>
          true
        }

    Kleisli[F, Request[F], Response[F]] { request =>
      if (isOpenCorsPolicyRequest(request)) {
        openCorsPolicy(httpApp).run(request)
      } else {
        primaryCorsPolicy(httpApp).run(request)
      }
    }
  }
}
