package com.ruchij.core.daos.doobie

import java.nio.file.{Path, Paths}
import java.sql.Timestamp
import java.util.concurrent.TimeUnit
import cats.Show
import com.ruchij.core.daos.videometadata.models.VideoSite
import doobie.util.{Get, Put}
import enumeratum.{Enum, EnumEntry}
import org.http4s.{MediaType, Uri}
import java.time.Instant
import java.time.temporal.ChronoUnit

import scala.concurrent.duration.FiniteDuration
import scala.reflect.runtime.universe.TypeTag
import scala.util.Try

object DoobieCustomMappings {
  private implicit def stringShow: Show[String] = Show.fromToString

  implicit val videoSitePut: Put[VideoSite] = Put[String].contramap[VideoSite](_.name.toLowerCase)

  implicit val videoSiteGet: Get[VideoSite] = Get[String].map(VideoSite.from)

  implicit def enumPut[A <: EnumEntry]: Put[A] = Put[String].contramap[A](_.entryName)

  implicit def enumGet[A <: EnumEntry: TypeTag](implicit enumValues: Enum[A]): Get[A] =
    Get[String].temap(text => enumValues.withNameInsensitiveEither(text).left.map(_.getMessage()))

  implicit val uriPut: Put[Uri] = Put[String].contramap[Uri](_.renderString)

  implicit val uriGet: Get[Uri] = Get[String].temap(text => Uri.fromString(text).left.map(_.message))

  implicit val finiteDurationPut: Put[FiniteDuration] = Put[Long].contramap[FiniteDuration](_.toMillis)

  implicit val finiteDurationGet: Get[FiniteDuration] =
    Get[Long].map(number => FiniteDuration(number, TimeUnit.MILLISECONDS))

  implicit val instantPut: Put[Instant] =
    Put[Timestamp].tcontramap[Instant](instant => Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS)))

  implicit val instantGet: Get[Instant] = Get[Timestamp].map(timestamp => timestamp.toInstant)

  implicit val pathPut: Put[Path] = Put[String].contramap[Path](_.toAbsolutePath.toString)

  implicit val pathGet: Get[Path] = Get[String].temap(text => Try(Paths.get(text)).toEither.left.map(_.getMessage))

  implicit val mediaTypePut: Put[MediaType] = Put[String].contramap[MediaType](Show[MediaType].show)

  implicit val mediaTypeGet: Get[MediaType] = Get[String].temap(value => MediaType.parse(value).left.map(_.message))
}
