package com.ruchij.web.responses

case class IterableResponse[Iter[x] <: Iterable[x], A](results: Iter[A])