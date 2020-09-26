package com.ruchij.api.web.responses

case class IterableResponse[Iter[x] <: Iterable[x], A](results: Iter[A])