package com.ruchij.core.exceptions

import org.jsoup.nodes.Element

case class TextNotFoundInElementException(element: Element) extends Exception
