package com.ruchij.api.web.middleware

import cats.data.Kleisli
import cats.effect.Sync
import com.ruchij.core.exceptions.ResourceNotFoundException
import org.http4s.{ContextRequest, ContextRoutes, Response}

object NotFoundHandler {

  def apply[F[_]: Sync, A](contextRoutes: ContextRoutes[A, F]): Kleisli[F, ContextRequest[F, A], Response[F]] =
    Kleisli[F, ContextRequest[F, A], Response[F]] { contextRequest =>
      contextRoutes.run(contextRequest).getOrElseF {
        Sync[F].raiseError {
          ResourceNotFoundException(s"Endpoint not found: ${contextRequest.req.method} ${contextRequest.req.uri}")
        }
      }
    }
}
