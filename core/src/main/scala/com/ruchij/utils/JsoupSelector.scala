package com.ruchij.utils

import cats.{Applicative, ApplicativeError, MonadError}
import cats.data.{Kleisli, NonEmptyList}
import cats.implicits._
import com.ruchij.daos.videometadata.models.VideoSite.Selector
import com.ruchij.exceptions.{MultipleElementsFoundException, NoMatchingElementsFoundException}
import org.jsoup.nodes.Element

import scala.jdk.CollectionConverters._

object JsoupSelector {

  def singleElement[F[_]: MonadError[*[_], Throwable]](css: String): Selector[F, Element] =
    nonEmptyElementList[F](css).flatMapF {
      case NonEmptyList(head, Nil) => Applicative[F].pure(head)
      case elements => ApplicativeError[F, Throwable].raiseError(MultipleElementsFoundException(elements))
    }

  def nonEmptyElementList[F[_]: MonadError[*[_], Throwable]](css: String): Selector[F, NonEmptyList[Element]] =
    select[F](css).flatMap {
      case Nil => Kleisli {
        document => ApplicativeError[F, Throwable].raiseError(NoMatchingElementsFoundException(document, css))
      }

      case head :: tail => Kleisli(_ => Applicative[F].pure(NonEmptyList(head, tail)))
    }

  def select[F[_]: ApplicativeError[*[_], Throwable]](css: String): Selector[F, List[Element]] =
    Kleisli { document =>
      ApplicativeError[F, Throwable]
        .catchNonFatal(document.select(css))
        .map(_.asScala.toList)
    }

}
