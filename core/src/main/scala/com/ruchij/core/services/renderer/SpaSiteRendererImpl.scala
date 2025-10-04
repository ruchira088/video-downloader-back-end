package com.ruchij.core.services.renderer

import cats.ApplicativeError
import cats.effect.Async
import cats.implicits.{catsSyntaxApplicativeError, catsSyntaxApplyOps}
import com.ruchij.core.config.SpaSiteRendererConfiguration
import com.ruchij.core.logging.Logger
import com.ruchij.core.services.renderer.models.{JavaScriptExecutionRequest, SpaRendererRequest}
import io.circe.generic.auto.exportEncoder
import org.http4s.Method.POST
import org.http4s.Uri
import org.http4s.Uri.Path
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.circe.encodeUri
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl

class SpaSiteRendererImpl[F[_]: Async](client: Client[F], spaSiteRendererConfiguration: SpaSiteRendererConfiguration)
    extends SpaSiteRenderer[F] {
  private val logger = Logger[SpaSiteRenderer[F]]

  private val clientDsl = Http4sClientDsl[F]

  import clientDsl._

  override def render(uri: Uri, readyCssSelectors: Seq[String]): F[String] =
    client.expect[String](
      POST(
        SpaRendererRequest(uri, readyCssSelectors),
        spaSiteRendererConfiguration.uri.withPath(Path.Root / "render")
      )
    )
      .recoverWith {
        case exception =>
          logger.warn[F](s"Unable to render uri=$uri")
            .productR { ApplicativeError[F, Throwable].raiseError(exception) }
      }

  override def executeJavaScript(uri: Uri, readyCssSelectors: Seq[String], script: String): F[String] =
    client.expect[String](
      POST(
        JavaScriptExecutionRequest(uri, readyCssSelectors, script),
        spaSiteRendererConfiguration.uri.withPath(Path.Root / "render" / "execute")
      )
    )
      .recoverWith {
        case exception =>
          logger.warn[F](s"Unable to execute JS for uri=$uri script=$script")
            .productR { ApplicativeError[F, Throwable].raiseError(exception) }
      }
}
