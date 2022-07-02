package com.ruchij.core.exceptions

import cats.data.NonEmptyList
import org.http4s.Uri
import org.jsoup.nodes.Element

trait JSoupException extends Exception {
  val element: Element
}

object JSoupException {
  case class AttributeNotFoundInElementException(element: Element, attributeKey: String) extends JSoupException {
    override def getMessage: String = s"Unable to find attribute=$attributeKey in element=$element"
  }

  case class MultipleElementsFoundException(uri: Uri, element: Element, css: String, result: NonEmptyList[Element])
      extends JSoupException {
    override def getMessage: String = s"Multiple elements found CSS=$css for element=$element at $uri"
  }

  case class NoMatchingElementsFoundException(uri: Uri, element: Element, css: String) extends JSoupException {
    override def getMessage: String =
      s"""
        Unable find element for css = $css at $uri in
        $element
      """
  }

  case class TextNotFoundInElementException(element: Element) extends JSoupException {
    override def getMessage: String = s"Text not found in $element"
  }
}
