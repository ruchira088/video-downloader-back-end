package com.ruchij.core.services.renderer.models

import org.http4s.Uri

case class JavaScriptExecutionRequest(url: Uri, readyCssSelectors: Seq[String], script: String)
