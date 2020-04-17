package com.ruchij.config

import org.joda.time.LocalTime
import pureconfig.ConfigReader
import pureconfig.error.ExceptionThrown

import scala.util.Try

object PureConfigReaders {
  implicit val localTimeReader: ConfigReader[LocalTime] =
    ConfigReader[String].emap(localTime => Try(LocalTime.parse(localTime)).toEither.left.map(ExceptionThrown.apply))
}
