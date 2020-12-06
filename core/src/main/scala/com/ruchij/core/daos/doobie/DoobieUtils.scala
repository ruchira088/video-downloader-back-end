package com.ruchij.core.daos.doobie

import cats.data.OptionT
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadError}
import com.ruchij.core.exceptions.InvalidConditionException
import com.ruchij.core.services.models.{Order, SortBy}
import doobie.implicits._
import doobie.util.fragment.Fragment

object DoobieUtils {
  def singleUpdate[F[_]: MonadError[*[_], Throwable]](result: F[Int]): OptionT[F, Unit] =
    OptionT {
      result.flatMap[Option[Unit]] {
        case 0 => Applicative[F].pure(None)
        case 1 => Applicative[F].pure(Some((): Unit))
        case count => ApplicativeError[F, Throwable].raiseError {
          InvalidConditionException {
            s"0 or 1 row was expected to be updated, but $count rows were updated"
          }
        }
      }
    }

  val sortByFieldName: PartialFunction[SortBy, Fragment] = {
    case SortBy.Size => fr"video_metadata.size"
    case SortBy.Duration => fr"video_metadata.duration"
    case SortBy.Title => fr"video_metadata.title"
    case SortBy.Random => fr"RANDOM()"
  }

  val ordering: Order => Fragment = {
    case Order.Ascending => fr"ASC"
    case Order.Descending => fr"DESC"
  }
}
