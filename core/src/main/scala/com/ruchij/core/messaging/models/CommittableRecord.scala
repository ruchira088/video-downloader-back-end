package com.ruchij.core.messaging.models

case class CommittableRecord[M[_], A](value: A, raw: M[A])
