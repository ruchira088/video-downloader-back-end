package com.ruchij.core.config

import com.comcast.ip4s.{Host, Port}
import enumeratum.{Enum, EnumEntry}
import org.http4s.Uri
import org.joda.time.LocalTime
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert

import scala.collection.Factory
import scala.reflect.ClassTag
import scala.util.Try

object PureConfigReaders {
  private val ListSeparator = ";"

  implicit val localTimePureConfigReader: ConfigReader[LocalTime] =
    stringConfigParserTry { localTime =>
      Try(LocalTime.parse(localTime))
    }

  implicit val hostConfigReader: ConfigReader[Host] = ConfigReader.fromNonEmptyStringOpt(Host.fromString)

  implicit val portConfigReader: ConfigReader[Port] = ConfigReader.fromNonEmptyStringOpt(Port.fromString)

  implicit val uriPureConfigReader: ConfigReader[Uri] =
    ConfigReader.fromNonEmptyString { input =>
      Uri.fromString(input).left.map(error => CannotConvert(input, classOf[Uri].getSimpleName, error.message))
    }

  implicit def stringIterableConfigReader[Itr[x] <: IterableOnce[x]](
    implicit factory: Factory[String, Itr[String]]
  ): ConfigReader[Itr[String]] =
    ConfigReader[String].map {
       string =>
        factory.fromSpecific {
          string.split(ListSeparator).map(_.trim).filter(_.nonEmpty)
        }
      }

  implicit def enumPureConfigReader[A <: EnumEntry: ClassTag](implicit enumValues: Enum[A]): ConfigReader[A] =
    ConfigReader.fromNonEmptyStringOpt[A] { value =>
      enumValues.values.find(_.entryName.equalsIgnoreCase(value))
    }

  private def stringConfigParserTry[A](parser: String => Try[A])(implicit classTag: ClassTag[A]): ConfigReader[A] =
    ConfigReader.fromNonEmptyString { value =>
      parser(value).toEither.left.map { throwable =>
        CannotConvert(value, classTag.runtimeClass.getSimpleName, throwable.getMessage)
      }
    }
}
