package com.ruchij.api.web.responses

final case class IterableResponse[Iter[x] <: Iterable[x], A](results: Iter[A])