package com.ruchij.core.utils

import cats.effect.IO
import com.ruchij.core.daos.videometadata.models.WebPage
import com.ruchij.core.exceptions.JSoupException._
import com.ruchij.core.test.IOSupport.runIO
import org.http4s.implicits.http4sLiteralsSyntax
import org.jsoup.Jsoup
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class JsoupSelectorSpec extends AnyFlatSpec with Matchers {

  val testHtml =
    """
      |<html>
      |<head><title>Test Page</title></head>
      |<body>
      |  <div class="container">
      |    <h1 id="title">Hello World</h1>
      |    <p class="content">First paragraph</p>
      |    <p class="content">Second paragraph</p>
      |    <img id="thumbnail" src="/images/thumb.jpg" alt="Thumbnail">
      |    <a href="https://example.com" class="link">Link</a>
      |    <span class="empty"></span>
      |  </div>
      |</body>
      |</html>
    """.stripMargin

  val document = Jsoup.parse(testHtml)
  val webPage = WebPage(uri"https://example.com/page", document)

  "JsoupSelector.singleElement" should "return single matching element" in runIO {
    JsoupSelector.singleElement[IO]("#title").run(webPage).map { element =>
      element.text() mustBe "Hello World"
    }
  }

  it should "raise error when multiple elements found" in runIO {
    JsoupSelector.singleElement[IO](".content").run(webPage).attempt.map { result =>
      result.isLeft mustBe true
      result.left.toOption.get mustBe a[MultipleElementsFoundException]
    }
  }

  it should "raise error when no elements found" in runIO {
    JsoupSelector.singleElement[IO](".nonexistent").run(webPage).attempt.map { result =>
      result.isLeft mustBe true
      result.left.toOption.get mustBe a[NoMatchingElementsFoundException]
    }
  }

  "JsoupSelector.nonEmptyElementList" should "return list of matching elements" in runIO {
    JsoupSelector.nonEmptyElementList[IO](".content").run(webPage).map { elements =>
      elements.size mustBe 2
      elements.head.text() mustBe "First paragraph"
      elements.tail.head.text() mustBe "Second paragraph"
    }
  }

  it should "raise error when no elements found" in runIO {
    JsoupSelector.nonEmptyElementList[IO](".missing").run(webPage).attempt.map { result =>
      result.isLeft mustBe true
      result.left.toOption.get mustBe a[NoMatchingElementsFoundException]
    }
  }

  "JsoupSelector.select" should "return empty list when no elements match" in runIO {
    JsoupSelector.select[IO](".nonexistent").run(webPage).map { elements =>
      elements mustBe List.empty
    }
  }

  it should "return list of elements when found" in runIO {
    JsoupSelector.select[IO]("p").run(webPage).map { elements =>
      elements.length mustBe 2
    }
  }

  "JsoupSelector.extractText" should "extract text from single element" in runIO {
    JsoupSelector.extractText[IO]("#title").run(webPage).map { text =>
      text mustBe "Hello World"
    }
  }

  "JsoupSelector.text" should "return element text" in runIO {
    val element = document.select("#title").first()
    JsoupSelector.text[IO](element).map { text =>
      text mustBe "Hello World"
    }
  }

  it should "raise error for empty text" in runIO {
    val element = document.select(".empty").first()
    JsoupSelector.text[IO](element).attempt.map { result =>
      result.isLeft mustBe true
      result.left.toOption.get mustBe a[TextNotFoundInElementException]
    }
  }

  "JsoupSelector.attribute" should "extract attribute value" in runIO {
    val element = document.select("#thumbnail").first()
    JsoupSelector.attribute[IO](element, "src").map { src =>
      src mustBe "/images/thumb.jpg"
    }
  }

  it should "raise error for missing attribute" in runIO {
    val element = document.select("#title").first()
    JsoupSelector.attribute[IO](element, "data-missing").attempt.map { result =>
      result.isLeft mustBe true
      result.left.toOption.get mustBe a[AttributeNotFoundInElementException]
    }
  }

  "JsoupSelector.stringToUri" should "parse absolute URL" in runIO {
    JsoupSelector.stringToUri[IO]("https://other.com/path").run(webPage.uri).map { uri =>
      uri.toString mustBe "https://other.com/path"
    }
  }

  it should "parse protocol-relative URL" in runIO {
    JsoupSelector.stringToUri[IO]("//cdn.example.com/image.jpg").run(webPage.uri).map { uri =>
      uri.toString mustBe "https://cdn.example.com/image.jpg"
    }
  }

  it should "parse relative path URL" in runIO {
    JsoupSelector.stringToUri[IO]("/images/photo.jpg").run(webPage.uri).map { uri =>
      uri.toString mustBe "https://example.com/images/photo.jpg"
    }
  }

  "JsoupSelector.src" should "extract src attribute as URI" in runIO {
    val element = document.select("#thumbnail").first()
    JsoupSelector.src[IO](element).run(webPage).map { uri =>
      uri.toString mustBe "https://example.com/images/thumb.jpg"
    }
  }
}
