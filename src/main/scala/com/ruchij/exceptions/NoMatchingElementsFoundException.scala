package com.ruchij.exceptions

import org.jsoup.nodes.Document

case class NoMatchingElementsFoundException(document: Document, css: String) extends Exception