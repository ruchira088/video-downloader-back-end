package com.ruchij.daos

import cats.data.OptionT
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadError}
import com.ruchij.exceptions.InvalidConditionException

package object doobie {
  def singleUpdate[F[_]: MonadError[*[_], Throwable]](result: F[Int]): OptionT[F, Unit] =
    OptionT {
      result.flatMap[Option[Unit]] {
        case 0 => Applicative[F].pure(None)
        case 1 => Applicative[F].pure(Some((): Unit))
        case _ => ApplicativeError[F, Throwable].raiseError(InvalidConditionException)
      }
    }
}
