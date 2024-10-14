package com.ruchij.api.web.responses

import cats.effect.Sync
import cats.implicits._
import com.ruchij.api.services.asset.models.Asset
import com.ruchij.core.types.FunctionKTypes._
import org.http4s.headers.{Range, `Accept-Ranges`, `Content-Length`, `Content-Range`, `Content-Type`}
import org.http4s.{Headers, Response, Status}

object ResponseOps {

  private def assetResponse[F[_]: Sync](asset: Asset[F]): F[Response[F]] =
    Sync[F].defer {
      `Content-Length`.fromLong(asset.fileRange.end - asset.fileRange.start)
        .toType[F, Throwable]
        .map { contentLength =>
          val headers =
            Headers(contentLength, `Content-Type`(asset.fileResource.mediaType), `Accept-Ranges`.bytes)

          if (contentLength.length < asset.fileResource.size)
            Response(
              status = Status.PartialContent,
              body = asset.stream,
              headers =
                headers ++
                  Headers(
                    `Content-Range`(
                      Range.SubRange(asset.fileRange.start, asset.fileRange.start + contentLength.length - 1),
                      Some(asset.fileResource.size)
                    )
                  )
            )
          else
            Response(status = Status.Ok, body = asset.stream, headers = headers)
        }
    }


  implicit class AssetResponseOps[F[_]: Sync](asset: Asset[F]) {
    val asResponse: F[Response[F]] = assetResponse[F](asset)
  }
}
