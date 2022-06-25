package com.ruchij.core.services.renderer.models

import org.http4s.Uri

case class SpaRendererRequest(url: Uri, readyCssSelectors: Seq[String])
