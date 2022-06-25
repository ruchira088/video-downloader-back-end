package com.ruchij.core.daos.videometadata.models

import org.http4s.Uri
import org.jsoup.nodes.Document

case class WebPage(uri: Uri, html: Document)
