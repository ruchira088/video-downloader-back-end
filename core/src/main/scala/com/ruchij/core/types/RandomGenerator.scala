package com.ruchij.core.types

import java.util.UUID

import cats.effect.Sync
import cats.implicits._
import cats.{Applicative, Monad}

trait RandomGenerator[F[+ _], +A] {
  val generate: F[A]
}

object RandomGenerator {
  def apply[F[+ _], A](implicit randomGenerator: RandomGenerator[F, A]): RandomGenerator[F, A] = randomGenerator

  def apply[F[+ _]: Sync, A](block: => A): RandomGenerator[F, A] =
    new RandomGenerator[F, A] {
      override val generate: F[A] = Sync[F].delay(block)
    }

  implicit def randomGeneratorMonad[F[+ _]: Monad]: Monad[RandomGenerator[F, *]] =
    new Monad[RandomGenerator[F, *]] {
      override def pure[A](x: A): RandomGenerator[F, A] =
        new RandomGenerator[F, A] {
          override val generate: F[A] = Applicative[F].pure(x)
        }

      override def flatMap[A, B](fa: RandomGenerator[F, A])(f: A => RandomGenerator[F, B]): RandomGenerator[F, B] =
        new RandomGenerator[F, B] {
          override val generate: F[B] = fa.generate.flatMap(a => f(a).generate)
        }

      override def tailRecM[A, B](a: A)(f: A => RandomGenerator[F, Either[A, B]]): RandomGenerator[F, B] =
        new RandomGenerator[F, B] {
          override val generate: F[B] = Monad[F].tailRecM(a)(value => f(value).generate)
        }
    }

  implicit def uuidGenerator[F[+ _]: Sync]: RandomGenerator[F, UUID] =
    new RandomGenerator[F, UUID] {
      override val generate: F[UUID] = Sync[F].delay(UUID.randomUUID())
    }
}
