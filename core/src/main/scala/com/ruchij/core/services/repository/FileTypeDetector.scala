package com.ruchij.core.services.repository


import org.http4s.MediaType

trait FileTypeDetector[F[_], -A] {
  def detect(key: A): F[MediaType]
}