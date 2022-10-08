package com.ruchij.core.types

import cats.data.NonEmptyList
import cats.effect.Sync
import cats.implicits._
import cats.{Applicative, Functor, Monad, MonadThrow}
import com.ruchij.core.exceptions.ValidationException
import org.joda.time.DateTime

import java.util.UUID
import scala.util.Random

trait RandomGenerator[F[_], A] {
  def generate: F[A]
}

object RandomGenerator {
  implicit class RandomGeneratorOps[F[_], A](randomGenerator: RandomGenerator[F, A]) {
    def evalMap[B](f: A => F[B])(implicit monad: Monad[F]): RandomGenerator[F, B] =
      new RandomGenerator[F, B] {
        override def generate: F[B] = randomGenerator.generate.flatMap(f)
      }
  }

  def apply[F[_], A](implicit randomGenerator: RandomGenerator[F, A]): RandomGenerator[F, A] = randomGenerator

  def apply[F[_]: Sync, A](block: => A): RandomGenerator[F, A] =
    new RandomGenerator[F, A] {
      override def generate: F[A] = Sync[F].delay[A](block)
    }

  def from[F[_]: Sync, A](values: NonEmptyList[A]): RandomGenerator[F, A] =
    range(0, values.length).map(index => values.toList(index))

  def eval[F[_]: Functor, A](value: F[A]): RandomGenerator[F, A] =
    new RandomGenerator[F, A] {
      override def generate: F[A] = value
    }

  def range[F[_]: Sync](start: Int, end: Int): RandomGenerator[F, Int] =
    new RandomGenerator[F, Int] {
      override def generate: F[Int] =
        if (start > end) MonadThrow[F].raiseError(ValidationException(s"$start must be less than $end"))
        else Sync[F].delay(Random.between(start, end))
    }

  implicit def dateTimeRandomGenerator[F[_]: JodaClock: Sync]: RandomGenerator[F, DateTime] =
    range(-10_000, 0).evalMap(offset => JodaClock[F].timestamp.map(_.minusMinutes(offset)))

  implicit def randomGeneratorMonad[F[_]: Monad]: Monad[RandomGenerator[F, *]] =
    new Monad[RandomGenerator[F, *]] {
      override def pure[A](x: A): RandomGenerator[F, A] =
        new RandomGenerator[F, A] {
          override def generate: F[A] = Applicative[F].pure[A](x)
        }

      override def flatMap[A, B](fa: RandomGenerator[F, A])(f: A => RandomGenerator[F, B]): RandomGenerator[F, B] =
        new RandomGenerator[F, B] {
          override def generate: F[B] = fa.generate.flatMap(a => f(a).generate)
        }

      override def tailRecM[A, B](a: A)(f: A => RandomGenerator[F, Either[A, B]]): RandomGenerator[F, B] =
        new RandomGenerator[F, B] {
          override def generate: F[B] =
            Monad[F].tailRecM(a)(value => f(value).generate)
        }
    }

  implicit def uuidGenerator[F[_]: Sync]: RandomGenerator[F, UUID] =
    new RandomGenerator[F, UUID] {
      override def generate: F[UUID] = Sync[F].delay(UUID.randomUUID())
    }
}
