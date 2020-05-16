package com.ruchij.utils

import cats.data.Kleisli
import cats.{Applicative, ApplicativeError}
import com.ruchij.daos.videometadata.models.VideoSite.Selector
import com.ruchij.exceptions.{MultipleElementsFoundException, NoMatchingElementsFoundException}
import org.jsoup.nodes.Element

object JsoupUtils {

  def query[F[_]: ApplicativeError[*[_], Throwable]](css: String): Selector[F, Element] =
    Kleisli { document =>
      val elements = document.select(css)

      elements.size() match {
        case 0 => ApplicativeError[F, Throwable].raiseError(NoMatchingElementsFoundException(document, css))
        case 1 => Applicative[F].pure(elements.first())
        case _ => ApplicativeError[F, Throwable].raiseError(MultipleElementsFoundException(elements))
      }
    }

}
