package com.ruchij.core.daos.videometadata.models

import org.http4s.Uri
import org.jsoup.nodes.Document

final case class WebPage(uri: Uri, html: Document)
