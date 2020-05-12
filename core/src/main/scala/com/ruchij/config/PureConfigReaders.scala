package com.ruchij.config

import org.joda.time.{DateTime, LocalTime}
import pureconfig.ConfigReader
import pureconfig.error.ExceptionThrown

import scala.util.Try

object PureConfigReaders {
  implicit val localTimePureConfigReader: ConfigReader[LocalTime] =
    jodaTimePureConfigReader {
      localTime => Try(LocalTime.parse(localTime))
    }

  implicit val dateTimePureConfigReader: ConfigReader[DateTime] =
    jodaTimePureConfigReader {
      dateTime => Try(DateTime.parse(dateTime))
    }

  def jodaTimePureConfigReader[A](parser: String => Try[A]): ConfigReader[A] =
    ConfigReader[String].emap {
      value => parser(value).toEither.left.map(ExceptionThrown.apply)
    }
}
