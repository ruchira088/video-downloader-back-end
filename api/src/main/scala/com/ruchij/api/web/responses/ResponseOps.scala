package com.ruchij.api.web.responses

import cats.ApplicativeError
import cats.implicits._
import com.ruchij.core.services.asset.models.Asset
import com.ruchij.core.types.FunctionKTypes.{FunctionK2TypeOps, eitherToF}
import com.ruchij.core.utils.Constants.ChunkSize
import org.http4s.headers.{Range, `Accept-Ranges`, `Content-Length`, `Content-Range`, `Content-Type`}
import org.http4s.{Headers, Response, Status}

object ResponseOps {

  def assetResponse[F[_]: ApplicativeError[*[_], Throwable]](asset: Asset[F], maxChunkSize: Long): F[Response[F]] =
    `Content-Length`.fromLong(Math.min(asset.fileRange.end - asset.fileRange.start, maxChunkSize))
      .toType[F, Throwable]
      .map { contentLength =>
        val headers =
          Headers.of(contentLength, `Content-Type`(asset.fileResource.mediaType), `Accept-Ranges`.bytes)

        if (contentLength.length < asset.fileResource.size)
          Response(
            status = Status.PartialContent,
            body = asset.stream.take(contentLength.length),
            headers =
              headers ++
                Headers.of(
                  `Content-Range`(
                    Range.SubRange(asset.fileRange.start, asset.fileRange.start + contentLength.length - 1),
                    Some(asset.fileResource.size)
                  )
                )
          )
        else
          Response(status = Status.Ok, body = asset.stream, headers = headers)
      }

  implicit class AssetResponseOps[F[_]: ApplicativeError[*[_], Throwable]](asset: Asset[F]) {
    def asResponse: F[Response[F]] = assetResponse(asset, ChunkSize)
  }
}
