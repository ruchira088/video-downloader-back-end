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

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

object DoobieCustomMappings {
  private given stringShow: Show[String] = Show.fromToString

  given videoSitePut: Put[VideoSite] = Put[String].contramap[VideoSite](_.name.toLowerCase)

  given videoSiteGet: Get[VideoSite] = Get[String].map(VideoSite.from)

  given enumPut[A <: EnumEntry]: Put[A] = Put[String].contramap[A](_.entryName)

  given enumGet[A <: EnumEntry](using enumValues: Enum[A]): Get[A] =
    Get[String].temap(text => enumValues.withNameInsensitiveEither(text).left.map(_.getMessage()))

  given uriPut: Put[Uri] = Put[String].contramap[Uri](_.renderString)

  given uriGet: Get[Uri] = Get[String].temap(text => Uri.fromString(text).left.map(_.message))

  given finiteDurationPut: Put[FiniteDuration] = Put[Long].contramap[FiniteDuration](_.toMillis)

  given finiteDurationGet: Get[FiniteDuration] =
    Get[Long].map(number => FiniteDuration(number, TimeUnit.MILLISECONDS))

  given instantPut: Put[Instant] = Put[Timestamp].tcontramap[Instant](Timestamp.from)

  given instantGet: Get[Instant] = Get[Timestamp].map(_.toInstant)

  given pathPut: Put[Path] = Put[String].contramap[Path](_.toAbsolutePath.toString)

  given pathGet: Get[Path] = Get[String].temap(text => Try(Paths.get(text)).toEither.left.map(_.getMessage))

  given mediaTypePut: Put[MediaType] = Put[String].contramap[MediaType](Show[MediaType].show)

  given mediaTypeGet: Get[MediaType] = Get[String].temap(value => MediaType.parse(value).left.map(_.message))

  given bigIntPut: Put[BigInt] = Put[String].contramap[BigInt](_.toString)

  given bigIntGet: Get[BigInt] = Get[String].temap(text => Try(BigInt(text)).toEither.left.map(_.getMessage))
}
