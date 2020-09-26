package com.ruchij.core.exceptions

import cats.data.NonEmptyList
import org.jsoup.nodes.Element

case class MultipleElementsFoundException(elements: NonEmptyList[Element]) extends Exception
