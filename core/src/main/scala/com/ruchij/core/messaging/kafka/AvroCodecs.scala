package com.ruchij.core.messaging.kafka

import cats.Show
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload.ErrorInfo
import com.ruchij.core.daos.videometadata.models.{VideoMetadata, VideoSite}
import enumeratum.{Enum, EnumEntry}
import org.http4s.{MediaType, Method, Status, Uri}
import vulcan.generic.*
import vulcan.{AvroError, Codec}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

object AvroCodecs {
  given videoSiteCodex: Codec[VideoSite] = Codec[String].imap(VideoSite.from)(_.name)

  given enumCodec[A <: EnumEntry](using enumValues: Enum[A]): Codec[A] =
    Codec[String].imapError[A] { input =>
      enumValues.withNameInsensitiveEither(input).left.map(error => AvroError(error.getMessage()))
    }(_.entryName)

  given finiteDurationCodec: Codec[FiniteDuration] =
    Codec[Long].imap(milliseconds => FiniteDuration(milliseconds, TimeUnit.MILLISECONDS))(_.toMillis)

  given uriCodec: Codec[Uri] =
    Codec[String].imapError(input => Uri.fromString(input).left.map(error => AvroError(error.message)))(_.renderString)

  given mediaTypeCodec: Codec[MediaType] =
    Codec[String].imapError { input =>
      MediaType.parse(input).left.map(error => AvroError(error.message))
    }(Show[MediaType].show)

  given statusCodec: Codec[Status] =
    Codec[Int].imapError(input => Status.fromInt(input).left.map(error => AvroError(error.message)))(_.code)

  given methodCodec: Codec[Method] =
    Codec[String].imapError(input => Method.fromString(input).left.map(error => AvroError(error.message)))(_.name)

  given fileResourceCodec: Codec[FileResource] = Codec.derive[FileResource]

  given videoMetadataCodec: Codec[VideoMetadata] = Codec.derive[VideoMetadata]

  given errorInfoCodec: Codec[ErrorInfo] = Codec.derive[ErrorInfo]
}
