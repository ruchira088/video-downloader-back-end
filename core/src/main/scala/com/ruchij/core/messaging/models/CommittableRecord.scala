package com.ruchij.core.messaging.models

final case class CommittableRecord[M[_], A](value: A, raw: M[A])
