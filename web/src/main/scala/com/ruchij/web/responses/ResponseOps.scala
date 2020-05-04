package com.ruchij.web.responses

import cats.ApplicativeError
import cats.implicits._
import com.ruchij.services.asset.models.Asset
import com.ruchij.types.FunctionKTypes.eitherToF
import org.http4s.headers.{Range, `Accept-Ranges`, `Content-Length`, `Content-Range`, `Content-Type`}
import org.http4s.{Headers, Response, Status}

object ResponseOps {

  def assetResponse[F[_]: ApplicativeError[*[_], Throwable]](asset: Asset[F]): F[Response[F]] =
    eitherToF[Throwable, F]
      .apply {
        `Content-Length`.fromLong(asset.fileResource.size - asset.fileRange.map(_.start).getOrElse(0L))
      }
      .map { contentLength =>
        Response()
          .withStatus(if (asset.fileRange.nonEmpty) Status.PartialContent else Status.Ok)
          .withHeaders {
            asset.fileRange.fold(Headers.empty) { range =>
              Headers.of(
                `Content-Range`.apply(
                  Range.SubRange(range.start, math.min(range.end, asset.fileResource.size - 1)),
                  Some(asset.fileResource.size)
                )
              )
            } ++
              Headers.of(contentLength, `Accept-Ranges`.bytes, `Content-Type`(asset.fileResource.mediaType))
          }
          .withBodyStream(asset.stream)
      }

  implicit class AssetResponseOps[F[_]: ApplicativeError[*[_], Throwable]](asset: Asset[F]) {
    def asResponse: F[Response[F]] = assetResponse(asset)
  }
}
