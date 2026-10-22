package com.ruchij.api.web.middleware

import cats.effect.IO
import cats.implicits._
import com.ruchij.core.test.IOSupport.runIO
import org.http4s._
import org.http4s.headers.Origin
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import org.typelevel.ci._

class CorsSpec extends AnyFlatSpec with Matchers {

  private val testApp: HttpApp[IO] = HttpApp[IO] { _ =>
    IO.pure(Response[IO](Status.Ok))
  }

  private def request(method: Method, uri: Uri, origin: String): Request[IO] =
    Request[IO](method, uri).putHeaders(Origin.parse(origin).toOption.get)

  private def preflight(uri: Uri, origin: String, requestMethod: Method): Request[IO] =
    request(Method.OPTIONS, uri, origin).putHeaders("Access-Control-Request-Method" -> requestMethod.name)

  private def header(response: Response[IO], name: CIString): Option[String] =
    response.headers.get(name).map(_.head.value)

  private def allowOrigin(response: Response[IO]): Option[String] =
    header(response, ci"Access-Control-Allow-Origin")

  private def allowCredentials(response: Response[IO]): Option[String] =
    header(response, ci"Access-Control-Allow-Credentials")

  private def mustAllow(response: Response[IO], origin: String) = {
    allowOrigin(response) mustBe Some(origin)
    allowCredentials(response) mustBe Some("true")
  }

  private def mustReject(response: Response[IO]) = {
    allowOrigin(response) mustBe None
    allowCredentials(response) mustBe None
  }

  "Cors middleware" should "allow credentialed requests from an allowed host" in runIO {
    Cors[IO](Set("example.com"))(testApp).run(request(Method.GET, uri"/test", "https://example.com")).map {
      response =>
        response.status mustBe Status.Ok
        mustAllow(response, "https://example.com")
    }
  }

  it should "allow an allowed host over HTTP and with an explicit port" in runIO {
    val corsApp = Cors[IO](Set("example.com"))(testApp)

    for {
      http <- corsApp.run(request(Method.GET, uri"/test", "http://example.com"))
      withPort <- corsApp.run(request(Method.GET, uri"/test", "https://example.com:8443"))
    } yield {
      mustAllow(http, "http://example.com")
      mustAllow(withPort, "https://example.com:8443")
    }
  }

  it should "not grant CORS access to an origin that is not allowed" in runIO {
    Cors[IO](Set("example.com"))(testApp).run(request(Method.GET, uri"/test", "https://other-domain.com")).map {
      response => mustReject(response)
    }
  }

  it should "not grant CORS access to origins that only look like an allowed host" in runIO {
    val corsApp = Cors[IO](Set("example.com"))(testApp)
    val lookAlikes =
      List("https://evilexample.com", "https://example.com.evil.com", "https://sub.example.com", "https://exampleXcom")

    lookAlikes
      .traverse(origin => corsApp.run(request(Method.GET, uri"/test", origin)))
      .map(_.foreach(mustReject))
  }

  it should "allow subdomains and the apex domain for a wildcard host" in runIO {
    val corsApp = Cors[IO](Set("*.example.com"))(testApp)

    for {
      subdomain <- corsApp.run(request(Method.GET, uri"/test", "https://app.example.com"))
      apex <- corsApp.run(request(Method.GET, uri"/test", "https://example.com"))
      lookAlike <- corsApp.run(request(Method.GET, uri"/test", "https://evilexample.com"))
    } yield {
      mustAllow(subdomain, "https://app.example.com")
      mustAllow(apex, "https://example.com")
      mustReject(lookAlike)
    }
  }

  it should "allow any origin with credentials on exactly the open endpoints" in runIO {
    val corsApp = Cors[IO](Set("example.com"))(testApp)
    val openEndpoints =
      List(
        Method.GET -> uri"/schedule/search",
        Method.POST -> uri"/schedule",
        Method.POST -> uri"/videos/metadata",
        Method.GET -> uri"/service/info"
      )

    openEndpoints
      .traverse { case (method, uri) => corsApp.run(request(method, uri, "https://any-domain.com")) }
      .map(_.foreach(response => mustAllow(response, "https://any-domain.com")))
  }

  it should "restrict other methods and paths next to the open endpoints" in runIO {
    val corsApp = Cors[IO](Set("example.com"))(testApp)
    val restrictedEndpoints =
      List(
        Method.GET -> uri"/schedule",
        Method.DELETE -> uri"/schedule/search",
        Method.POST -> uri"/schedule/retry-failed",
        Method.GET -> uri"/videos/metadata",
        Method.POST -> uri"/users",
        Method.POST -> uri"/authentication/login"
      )

    restrictedEndpoints
      .traverse { case (method, uri) => corsApp.run(request(method, uri, "https://any-domain.com")) }
      .map(_.foreach(mustReject))
  }

  it should "answer preflight requests for open endpoints from any origin" in runIO {
    Cors[IO](Set("example.com"))(testApp).run(preflight(uri"/schedule", "https://any-domain.com", Method.POST)).map {
      response => mustAllow(response, "https://any-domain.com")
    }
  }

  it should "not grant preflight access to a disallowed origin on a restricted endpoint" in runIO {
    val corsApp = Cors[IO](Set("example.com"))(testApp)

    for {
      disallowed <- corsApp.run(preflight(uri"/users", "https://any-domain.com", Method.POST))
      allowed <- corsApp.run(preflight(uri"/users", "https://example.com", Method.POST))
    } yield {
      mustReject(disallowed)
      mustAllow(allowed, "https://example.com")
    }
  }

  it should "propagate the response from the underlying app" in runIO {
    val customApp: HttpApp[IO] = HttpApp[IO] { _ =>
      IO.pure(Response[IO](Status.Created))
    }

    Cors[IO](Set("example.com"))(customApp).run(request(Method.POST, uri"/test", "https://example.com")).map {
      response =>
        response.status mustBe Status.Created
        mustAllow(response, "https://example.com")
    }
  }
}
