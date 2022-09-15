package com.ruchij.core.utils

import cats.data.{Kleisli, NonEmptyList}
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadThrow}
import com.ruchij.core.daos.videometadata.models.CustomVideoSite.Selector
import com.ruchij.core.daos.videometadata.models.WebPage
import com.ruchij.core.exceptions.JSoupException._
import com.ruchij.core.types.FunctionKTypes._
import org.http4s.Uri
import org.jsoup.nodes.Element

import scala.jdk.CollectionConverters._

object JsoupSelector {

  def singleElement[F[_]: MonadThrow](css: String): Selector[F, Element] =
    nonEmptyElementList[F](css).flatMap {
      case NonEmptyList(head, Nil) => Kleisli.pure(head)
      case elements =>
        Kleisli.ask[F, WebPage].flatMapF { webPage =>
          ApplicativeError[F, Throwable].raiseError(MultipleElementsFoundException(webPage.uri, webPage.html, css, elements))
        }
    }

  def nonEmptyElementList[F[_]: MonadThrow](css: String): Selector[F, NonEmptyList[Element]] =
    select[F](css).flatMap {
      case Nil =>
        Kleisli { webPage =>
          ApplicativeError[F, Throwable].raiseError(NoMatchingElementsFoundException(webPage.uri, webPage.html, css))
        }

      case head :: tail => Kleisli(_ => Applicative[F].pure(NonEmptyList(head, tail)))
    }

  def select[F[_]: ApplicativeError[*[_], Throwable]](css: String): Selector[F, List[Element]] =
    Kleisli { webPage =>
      ApplicativeError[F, Throwable]
        .catchNonFatal(webPage.html.select(css))
        .map(_.asScala.toList)
    }

  def text[F[_]: ApplicativeError[*[_], Throwable]](element: Element): F[String] =
    parseProperty[Throwable, F](element.text(), TextNotFoundInElementException(element))

  def selectText[F[_]: MonadThrow](css: String): Selector[F, String] =
    singleElement[F](css).flatMapF(text[F])

  def src[F[_]: MonadThrow](element: Element): F[Uri] =
    attribute[F](element, "src")
      .flatMap { uri =>
        Uri.fromString(uri).toType[F, Throwable]
      }

  def attribute[F[_]: ApplicativeError[*[_], Throwable]](element: Element, attributeKey: String): F[String] =
    parseProperty[Throwable, F](element.attr(attributeKey), AttributeNotFoundInElementException(element, attributeKey))

  private def parseProperty[E, F[_]: ApplicativeError[*[_], E]](value: String, onEmpty: => E): F[String] =
    Option(value)
      .map(_.trim)
      .filter(_.nonEmpty)
      .fold[F[String]](ApplicativeError[F, E].raiseError(onEmpty)) { string =>
        Applicative[F].pure(string)
      }

}
