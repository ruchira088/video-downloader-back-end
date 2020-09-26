package com.ruchij.core.exceptions

import org.jsoup.nodes.Element

case class AttributeNotFoundInElementException(element: Element, attributeKey: String) extends Exception
