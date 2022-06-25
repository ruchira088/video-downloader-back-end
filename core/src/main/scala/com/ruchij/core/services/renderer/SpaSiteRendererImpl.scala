package com.ruchij.core.services.renderer

import cats.effect.Async
import com.ruchij.core.config.SpaSiteRendererConfiguration
import com.ruchij.core.services.renderer.models.SpaRendererRequest
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
  private val clientDsl = Http4sClientDsl[F]

  import clientDsl._

  override def render(uri: Uri, readyCssSelectors: Seq[String]): F[String] =
    client.expect[String](
      POST(
        SpaRendererRequest(uri, readyCssSelectors),
        spaSiteRendererConfiguration.uri.withPath(Path.Root / "render")
      )
    )

}
