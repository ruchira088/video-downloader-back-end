package com.ruchij.core.config

import org.http4s.Uri
import org.joda.time.{DateTime, LocalTime}
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert

import scala.reflect.ClassTag
import scala.util.Try

object PureConfigReaders {
  implicit val localTimePureConfigReader: ConfigReader[LocalTime] =
    stringConfigParserTry {
      localTime => Try(LocalTime.parse(localTime))
    }

  implicit val dateTimePureConfigReader: ConfigReader[DateTime] =
    stringConfigParserTry {
      dateTime => Try(DateTime.parse(dateTime))
    }

  implicit val uriPureConfigReader: ConfigReader[Uri] =
    ConfigReader.fromNonEmptyString { input =>
      Uri.fromString(input).left.map(error => CannotConvert(input, classOf[Uri].getSimpleName, error.message))
    }

  def stringConfigParserTry[A](parser: String => Try[A])(implicit classTag: ClassTag[A]): ConfigReader[A] =
    ConfigReader.fromNonEmptyString {
      value =>
        parser(value).toEither.left.map {
          throwable => CannotConvert(value, classTag.runtimeClass.getSimpleName, throwable.getMessage)
        }
    }
}
