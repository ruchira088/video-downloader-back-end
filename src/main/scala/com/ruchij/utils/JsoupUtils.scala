package com.ruchij.utils

import cats.{Applicative, MonadError}
import com.ruchij.exceptions.{MultipleElementsFoundException, NoMatchingElementsFoundException}
import org.jsoup.nodes.{Document, Element}

object JsoupUtils {
  def query[F[_]: MonadError[*[_], Throwable]](document: Document, css: String): F[Element] = {
    val elements = document.select(css)

    elements.size() match {
      case 0 => MonadError[F, Throwable].raiseError(NoMatchingElementsFoundException(document, css))
      case 1 => Applicative[F].pure(elements.first())
      case _ => MonadError[F, Throwable].raiseError(MultipleElementsFoundException(elements))
    }
  }
}
