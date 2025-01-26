package com.ruchij.batch.utils

import org.http4s.MediaType

object Constants {
  private val CustomVideoFileExtensions = Set("vid")

  lazy val VideoFileExtensions: List[String] =
    MediaType.video.all.flatMap(_.fileExtensions).concat(CustomVideoFileExtensions)
}
