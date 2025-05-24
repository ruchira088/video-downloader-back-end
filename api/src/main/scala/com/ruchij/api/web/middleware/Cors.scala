package com.ruchij.api.web.middleware

import org.http4s.server.middleware.{CORS, CORSPolicy}

object Cors {
  private def hostRegex(host: String): Set[String] =
    Set(host)
      .flatMap(origin => if (origin.startsWith("*.")) Set(origin.substring(2), origin) else Set(origin))
      .map(origin => s"https?://${origin.replace(".", "\\.").replace("*", ".*")}(:\\d+)?$$")

  def apply(allowedHosts: Set[String]): CORSPolicy = {
    val hostsRegex = allowedHosts.flatMap(hostRegex).mkString("|").r

    CORS.policy
      .withAllowCredentials(true)
      .withAllowOriginHostCi { originHost =>
        hostsRegex.matches(originHost.toString)
      }
  }
}
