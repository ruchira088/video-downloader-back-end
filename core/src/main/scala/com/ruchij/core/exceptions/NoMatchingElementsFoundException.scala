package com.ruchij.core.exceptions

import org.jsoup.nodes.Element

case class NoMatchingElementsFoundException(element: Element, css: String) extends Exception