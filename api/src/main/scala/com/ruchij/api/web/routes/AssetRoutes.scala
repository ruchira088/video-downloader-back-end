package com.ruchij.api.web.routes

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Sync
import cats.implicits._
import com.ruchij.api.services.asset.AssetService
import com.ruchij.api.services.models.Context.AuthenticatedRequestContext
import com.ruchij.api.web.responses.ResponseOps.AssetResponseOps
import AssetService.FileByteRange
import org.http4s.{ContextRoutes, RangeUnit}
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Range

object AssetRoutes {
  private val MaxVideoServeSize = 5 * 1000 * 1000 // 5MB

  /** http4s parses a suffix range (bytes=-500) as SubRange(-500, None). Invalid ranges are ignored (RFC 7233). */
  private def fileByteRange(subRange: Range.SubRange): Option[FileByteRange] =
    subRange match {
      case Range.SubRange(first, None) if first < 0 => Some(FileByteRange.SuffixRange(-first))
      case Range.SubRange(first, Some(last)) if first < 0 || last < first => None
      case Range.SubRange(first, maybeLast) => Some(FileByteRange.ByteRange(first, maybeLast))
    }

  def apply[F[_]: Sync](assetService: AssetService[F])(implicit dsl: Http4sDsl[F]): ContextRoutes[AuthenticatedRequestContext, F] = {
    import dsl._

    ContextRoutes.of[AuthenticatedRequestContext, F] {
      case GET -> Root / "thumbnail" / "id" / id as _ =>
          assetService.thumbnail(id).flatMap(_.asResponse)

      case GET -> Root / "snapshot" / "id" / id as AuthenticatedRequestContext(user, _) =>
        assetService.snapshot(id, user).flatMap(_.asResponse)

      case GET -> Root / "album-art" / "id" / id as AuthenticatedRequestContext(user, _) =>
        assetService.albumArt(id, user).flatMap(_.asResponse)

      case authRequest @ GET -> Root / "video" / "id" / id as AuthenticatedRequestContext(user, _) =>
        for {
          maybeFileByteRange <- Applicative[F].pure {
            authRequest.req.headers
              .get[Range]
              .collect { case Range(RangeUnit.Bytes, NonEmptyList(subRange, _)) => subRange }
              .flatMap(fileByteRange)
          }

          videoFileAsset <-
            assetService.videoFile(
              id,
              user,
              maybeFileByteRange,
              Some(MaxVideoServeSize)
            )

          response <- videoFileAsset.asResponse
        }
        yield response
    }
  }
}
