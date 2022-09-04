package com.ruchij.core.services.renderer.models

import org.http4s.Uri

final case class SpaRendererRequest(url: Uri, readyCssSelectors: Seq[String])
