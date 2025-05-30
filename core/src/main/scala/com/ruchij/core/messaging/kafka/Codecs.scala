package com.ruchij.core.messaging.kafka

import java.time.Instant
import java.util.concurrent.TimeUnit
import cats.Show
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload.ErrorInfo
import com.ruchij.core.daos.videometadata.models.{VideoMetadata, VideoSite}
import enumeratum.{Enum, EnumEntry}
import org.http4s.{MediaType, Method, Status, Uri}
import org.joda.time.DateTime
import vulcan.{AvroError, Codec}
import vulcan.generic._

import scala.concurrent.duration.FiniteDuration

object Codecs {
  implicit val dateTimeCodec: Codec[DateTime] =
    Codec[Instant].imap[DateTime](instant => new DateTime(instant.toEpochMilli)) { dateTime =>
      Instant.ofEpochMilli(dateTime.getMillis)
    }

  implicit val videoSiteCodex: Codec[VideoSite] = Codec[String].imap(VideoSite.from)(_.name)

  implicit def enumCodec[A <: EnumEntry](implicit enumValues: Enum[A]): Codec[A] =
    Codec[String].imapError[A] {
      input => enumValues.withNameInsensitiveEither(input).left.map(error => AvroError(error.getMessage()))
    }(_.entryName)

  implicit val finiteDurationCodec: Codec[FiniteDuration] =
    Codec[Long].imap(milliseconds => FiniteDuration(milliseconds, TimeUnit.MILLISECONDS))(_.toMillis)

  implicit val uriCodec: Codec[Uri] =
    Codec[String].imapError(input => Uri.fromString(input).left.map(error => AvroError(error.message)))(_.renderString)

  implicit val mediaTypeCodec: Codec[MediaType] =
    Codec[String].imapError {
      input => MediaType.parse(input).left.map(error => AvroError(error.message))
    }(Show[MediaType].show)

  implicit val statusCodec: Codec[Status] =
    Codec[Int].imapError(input => Status.fromInt(input).left.map(error => AvroError(error.message)))(_.code)

  implicit val methodCodec: Codec[Method] =
    Codec[String].imapError(input => Method.fromString(input).left.map(error => AvroError(error.message)))(_.name)

  implicit val fileResourceCodec: Codec[FileResource] = Codec.derive[FileResource]

  implicit val videoMetadataCodec: Codec[VideoMetadata] = Codec.derive[VideoMetadata]

  implicit val errorInfoCodec: Codec[ErrorInfo] = Codec.derive[ErrorInfo]
}
