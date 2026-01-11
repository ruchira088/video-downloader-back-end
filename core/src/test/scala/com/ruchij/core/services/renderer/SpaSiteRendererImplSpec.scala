package com.ruchij.core.services.renderer

import cats.effect.IO
import com.ruchij.core.config.SpaSiteRendererConfiguration
import com.ruchij.core.test.IOSupport.runIO
import fs2.Stream
import org.http4s._
import org.http4s.client.Client
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class SpaSiteRendererImplSpec extends AnyFlatSpec with Matchers {

  val rendererConfig = SpaSiteRendererConfiguration(uri"http://localhost:8000")

  "SpaSiteRendererImpl.render" should "send POST request and return rendered HTML" in runIO {
    val expectedHtml = "<html><body>Rendered Content</body></html>"

    val client = Client.fromHttpApp[IO](HttpApp.liftF(IO.pure(
      Response[IO](
        status = Status.Ok,
        body = Stream.emits(expectedHtml.getBytes)
      )
    )))

    val renderer = new SpaSiteRendererImpl[IO](client, rendererConfig)

    for {
      result <- renderer.render(uri"https://example.com/page", Seq(".content"))
      _ <- IO.delay {
        result mustBe expectedHtml
      }
    } yield ()
  }

  it should "handle error responses and re-raise exception" in runIO {
    val client = Client.fromHttpApp[IO](HttpApp.liftF(IO.raiseError[Response[IO]](
      new RuntimeException("Server error")
    )))

    val renderer = new SpaSiteRendererImpl[IO](client, rendererConfig)

    renderer.render(uri"https://example.com/page", Seq(".content"))
      .attempt
      .flatMap { result =>
        IO.delay {
          result.isLeft mustBe true
          result.left.toOption.get.getMessage mustBe "Server error"
        }
      }
  }

  "SpaSiteRendererImpl.executeJavaScript" should "send POST request with script and return result" in runIO {
    val expectedResult = "JavaScript Result"

    val client = Client.fromHttpApp[IO](HttpApp.liftF(IO.pure(
      Response[IO](
        status = Status.Ok,
        body = Stream.emits(expectedResult.getBytes)
      )
    )))

    val renderer = new SpaSiteRendererImpl[IO](client, rendererConfig)

    for {
      result <- renderer.executeJavaScript(
        uri"https://example.com/page",
        Seq(".ready"),
        "document.querySelector('.data').textContent"
      )
      _ <- IO.delay {
        result mustBe expectedResult
      }
    } yield ()
  }

  it should "handle error responses and re-raise exception for JS" in runIO {
    val client = Client.fromHttpApp[IO](HttpApp.liftF(IO.raiseError[Response[IO]](
      new RuntimeException("Script execution failed")
    )))

    val renderer = new SpaSiteRendererImpl[IO](client, rendererConfig)

    renderer.executeJavaScript(uri"https://example.com/page", Seq(".ready"), "script")
      .attempt
      .flatMap { result =>
        IO.delay {
          result.isLeft mustBe true
          result.left.toOption.get.getMessage mustBe "Script execution failed"
        }
      }
  }

  it should "handle empty selectors list" in runIO {
    val expectedHtml = "<html></html>"

    val client = Client.fromHttpApp[IO](HttpApp.liftF(IO.pure(
      Response[IO](
        status = Status.Ok,
        body = Stream.emits(expectedHtml.getBytes)
      )
    )))

    val renderer = new SpaSiteRendererImpl[IO](client, rendererConfig)

    for {
      result <- renderer.render(uri"https://example.com/page", Seq.empty)
      _ <- IO.delay {
        result mustBe expectedHtml
      }
    } yield ()
  }
}
