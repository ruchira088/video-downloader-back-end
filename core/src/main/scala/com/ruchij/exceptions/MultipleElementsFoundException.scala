package com.ruchij.exceptions

import cats.data.NonEmptyList
import org.jsoup.nodes.Element

case class MultipleElementsFoundException(elements: NonEmptyList[Element]) extends Exception
