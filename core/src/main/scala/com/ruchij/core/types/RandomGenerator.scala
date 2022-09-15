package com.ruchij.core.types

import java.util.UUID

import cats.effect.Sync
import cats.implicits._
import cats.{Applicative, Monad}

trait RandomGenerator[F[_], +A] {
  def generate[B >: A]: F[B]
}

object RandomGenerator {
  def apply[F[_], A](implicit randomGenerator: RandomGenerator[F, A]): RandomGenerator[F, A] = randomGenerator

  def apply[F[_]: Sync, A](block: => A): RandomGenerator[F, A] =
    new RandomGenerator[F, A] {
      override def generate[B >: A]: F[B] = Sync[F].delay[B](block)
    }

  implicit def randomGeneratorMonad[F[_]: Monad]: Monad[RandomGenerator[F, *]] =
    new Monad[RandomGenerator[F, *]] {
      override def pure[A](x: A): RandomGenerator[F, A] =
        new RandomGenerator[F, A] {
          override def generate[B >: A]: F[B] = Applicative[F].pure[B](x)
        }

      override def flatMap[A, B](fa: RandomGenerator[F, A])(f: A => RandomGenerator[F, B]): RandomGenerator[F, B] =
        new RandomGenerator[F, B] {
          override def generate[C >: B]: F[C] = fa.generate.flatMap(a => f(a).generate[C])
        }

      override def tailRecM[A, B](a: A)(f: A => RandomGenerator[F, Either[A, B]]): RandomGenerator[F, B] =
        new RandomGenerator[F, B] {
          override def generate[C >: B]: F[C] =
            Monad[F].tailRecM(a)(value => f(value).generate[Either[A, C]])
        }
    }

  implicit def uuidGenerator[F[_]: Sync]: RandomGenerator[F, UUID] =
    new RandomGenerator[F, UUID] {
      override def generate[A >: UUID]: F[A] = Sync[F].delay[A](UUID.randomUUID())
    }
}
