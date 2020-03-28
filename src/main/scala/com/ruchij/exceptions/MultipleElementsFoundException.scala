package com.ruchij.exceptions

import org.jsoup.select.Elements

case class MultipleElementsFoundException(elements: Elements) extends Exception
