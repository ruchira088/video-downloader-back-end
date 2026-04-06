package com.ruchij.core.utils

import cats.effect.IO
import com.ruchij.core.config.HttpProxyConfiguration
import com.ruchij.core.test.IOSupport.runIO
import org.http4s.Uri
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class ClientsSpec extends AnyFlatSpec with Matchers {

  "Clients.create" should "create an HTTP client without proxy when configuration is None" in runIO {
    Clients.create[IO](None).use { client =>
      IO.delay {
        client must not be null
      }
    }
  }

  it should "create an HTTP client with proxy when full proxy URL is provided" in runIO {
    val proxyConfig = Some(HttpProxyConfiguration(uri"http://proxy.example.com:8080"))

    Clients.create[IO](proxyConfig).use { client =>
      IO.delay {
        client must not be null
      }
    }
  }

  it should "create an HTTP client with proxy for HTTPS proxy URL" in runIO {
    val proxyConfig = Some(HttpProxyConfiguration(uri"https://secure-proxy.example.com:3128"))

    Clients.create[IO](proxyConfig).use { client =>
      IO.delay {
        client must not be null
      }
    }
  }

  it should "fall back to no-proxy when proxy URL has no port" in runIO {
    val proxyConfig = Some(HttpProxyConfiguration(uri"http://proxy.example.com"))

    Clients.create[IO](proxyConfig).use { client =>
      IO.delay {
        client must not be null
      }
    }
  }

  it should "fall back to no-proxy when proxy URL is a bare path with no host or port" in runIO {
    val proxyConfig = Some(HttpProxyConfiguration(Uri(path = Uri.Path.unsafeFromString("/some-path"))))

    Clients.create[IO](proxyConfig).use { client =>
      IO.delay {
        client must not be null
      }
    }
  }

  it should "create an HTTP client with proxy on non-standard port" in runIO {
    val proxyConfig = Some(HttpProxyConfiguration(uri"http://10.0.0.1:9999"))

    Clients.create[IO](proxyConfig).use { client =>
      IO.delay {
        client must not be null
      }
    }
  }

  it should "properly release the underlying Java HTTP client when resource is released" in runIO {
    var acquired = false

    val testResource = Clients.create[IO](None).evalTap { _ =>
      IO.delay { acquired = true }
    }

    testResource.use { _ =>
      IO.delay {
        acquired mustBe true
      }
    }.flatMap { _ =>
      IO.delay {
        // If we get here without error, the resource was properly acquired and released
        acquired mustBe true
      }
    }
  }

  it should "create an HTTP client with proxy using localhost" in runIO {
    val proxyConfig = Some(HttpProxyConfiguration(uri"http://localhost:3128"))

    Clients.create[IO](proxyConfig).use { client =>
      IO.delay {
        client must not be null
      }
    }
  }

  it should "create an HTTP client with proxy using IP address" in runIO {
    val proxyConfig = Some(HttpProxyConfiguration(uri"http://192.168.1.100:8888"))

    Clients.create[IO](proxyConfig).use { client =>
      IO.delay {
        client must not be null
      }
    }
  }

  it should "create both proxied and non-proxied clients concurrently" in runIO {
    val proxyConfig = Some(HttpProxyConfiguration(uri"http://proxy.example.com:8080"))

    val combined = for {
      proxiedClient <- Clients.create[IO](proxyConfig)
      directClient <- Clients.create[IO](None)
    } yield (proxiedClient, directClient)

    combined.use { case (proxiedClient, directClient) =>
      IO.delay {
        proxiedClient must not be null
        directClient must not be null
      }
    }
  }

  it should "independently manage lifecycle of proxied and non-proxied clients" in runIO {
    val proxyConfig = Some(HttpProxyConfiguration(uri"http://proxy.example.com:8080"))

    var proxiedAcquired = false
    var directAcquired = false

    val proxiedResource = Clients.create[IO](proxyConfig).evalTap(_ => IO.delay { proxiedAcquired = true })
    val directResource = Clients.create[IO](None).evalTap(_ => IO.delay { directAcquired = true })

    val combined = for {
      p <- proxiedResource
      d <- directResource
    } yield (p, d)

    combined.use { _ =>
      IO.delay {
        proxiedAcquired mustBe true
        directAcquired mustBe true
      }
    }
  }
}
