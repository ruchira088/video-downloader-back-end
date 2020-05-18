package com.ruchij.exceptions

import org.jsoup.nodes.Element

case class NoMatchingElementsFoundException(element: Element, css: String) extends Exception