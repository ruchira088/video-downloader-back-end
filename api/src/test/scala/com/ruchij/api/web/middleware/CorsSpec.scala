package com.ruchij.api.web.middleware

import cats.effect.IO
import com.ruchij.core.test.IOSupport.runIO
import org.http4s._
import org.http4s.headers.Origin
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class CorsSpec extends AnyFlatSpec with Matchers {

  private val testApp: HttpApp[IO] = HttpApp[IO] { _ =>
    IO.pure(Response[IO](Status.Ok))
  }

  "Cors middleware" should "allow requests from allowed hosts" in runIO {
    val corsApp = Cors[IO](Set("example.com"))(testApp)
    val request = Request[IO](Method.GET, uri"/test")
      .putHeaders(Origin.parse("https://example.com").toOption.get)

    corsApp.run(request).map { response =>
      response.status mustBe Status.Ok
    }
  }

  it should "apply open CORS policy for schedule search endpoint" in runIO {
    val corsApp = Cors[IO](Set("example.com"))(testApp)
    val request = Request[IO](Method.GET, uri"/schedule/search")
      .putHeaders(Origin.parse("https://other-domain.com").toOption.get)

    corsApp.run(request).map { response =>
      response.status mustBe Status.Ok
    }
  }

  it should "apply open CORS policy for schedule POST endpoint" in runIO {
    val corsApp = Cors[IO](Set("example.com"))(testApp)
    val request = Request[IO](Method.POST, uri"/schedule")
      .putHeaders(Origin.parse("https://any-domain.com").toOption.get)

    corsApp.run(request).map { response =>
      response.status mustBe Status.Ok
    }
  }

  it should "apply open CORS policy for videos metadata endpoint" in runIO {
    val corsApp = Cors[IO](Set("example.com"))(testApp)
    val request = Request[IO](Method.POST, uri"/videos/metadata")
      .putHeaders(Origin.parse("https://any-domain.com").toOption.get)

    corsApp.run(request).map { response =>
      response.status mustBe Status.Ok
    }
  }

  it should "apply open CORS policy for service info endpoint" in runIO {
    val corsApp = Cors[IO](Set("example.com"))(testApp)
    val request = Request[IO](Method.GET, uri"/service/info")
      .putHeaders(Origin.parse("https://any-domain.com").toOption.get)

    corsApp.run(request).map { response =>
      response.status mustBe Status.Ok
    }
  }

  it should "apply open CORS policy for OPTIONS preflight requests" in runIO {
    val corsApp = Cors[IO](Set("example.com"))(testApp)
    val request = Request[IO](Method.OPTIONS, uri"/schedule/search")
      .putHeaders(Origin.parse("https://other-domain.com").toOption.get)

    corsApp.run(request).map { response =>
      response.status mustBe Status.Ok
    }
  }

  it should "handle wildcard subdomains" in runIO {
    val corsApp = Cors[IO](Set("*.example.com"))(testApp)
    val request = Request[IO](Method.GET, uri"/test")
      .putHeaders(Origin.parse("https://sub.example.com").toOption.get)

    corsApp.run(request).map { response =>
      response.status mustBe Status.Ok
    }
  }

  it should "handle multiple allowed hosts" in runIO {
    val corsApp = Cors[IO](Set("example.com", "other.com"))(testApp)
    val request = Request[IO](Method.GET, uri"/test")
      .putHeaders(Origin.parse("https://other.com").toOption.get)

    corsApp.run(request).map { response =>
      response.status mustBe Status.Ok
    }
  }

  it should "allow localhost with port" in runIO {
    val corsApp = Cors[IO](Set("localhost"))(testApp)
    val request = Request[IO](Method.GET, uri"/test")
      .putHeaders(Origin.parse("http://localhost:3000").toOption.get)

    corsApp.run(request).map { response =>
      response.status mustBe Status.Ok
    }
  }

  it should "work with empty allowed hosts set" in runIO {
    val corsApp = Cors[IO](Set.empty[String])(testApp)
    val request = Request[IO](Method.GET, uri"/test")

    corsApp.run(request).map { response =>
      response.status mustBe Status.Ok
    }
  }

  it should "handle HTTP scheme" in runIO {
    val corsApp = Cors[IO](Set("example.com"))(testApp)
    val request = Request[IO](Method.GET, uri"/test")
      .putHeaders(Origin.parse("http://example.com").toOption.get)

    corsApp.run(request).map { response =>
      response.status mustBe Status.Ok
    }
  }

  it should "handle HTTPS scheme" in runIO {
    val corsApp = Cors[IO](Set("example.com"))(testApp)
    val request = Request[IO](Method.GET, uri"/test")
      .putHeaders(Origin.parse("https://example.com").toOption.get)

    corsApp.run(request).map { response =>
      response.status mustBe Status.Ok
    }
  }

  it should "propagate response from underlying app" in runIO {
    val customApp: HttpApp[IO] = HttpApp[IO] { _ =>
      IO.pure(Response[IO](Status.Created))
    }
    val corsApp = Cors[IO](Set("example.com"))(customApp)
    val request = Request[IO](Method.POST, uri"/test")
      .putHeaders(Origin.parse("https://example.com").toOption.get)

    corsApp.run(request).map { response =>
      response.status mustBe Status.Created
    }
  }
}
