package com.ruchij.core.services.renderer

import org.http4s.Uri

trait SpaSiteRenderer[F[_]] {
  def render(uri: Uri, readyCssSelectors: Seq[String]): F[String]

  def executeJavaScript(uri: Uri, readyCssSelectors: Seq[String], script: String): F[String]
}
