package com.ruchij.api.web.requests

import cats.data.EitherT
import cats.effect.kernel.Async
import com.ruchij.api.services.playlist.PlaylistService
import fs2.Stream
import org.http4s.multipart.Multipart
import org.http4s.{DecodeResult, EntityDecoder, InvalidMessageBodyFailure, MediaType}

final case class FileAsset[F[_]](fileName: String, mediaType: MediaType, data: Stream[F, Byte])

object FileAsset {
  implicit def fileAssetDecoder[F[_]: Async]: EntityDecoder[F, FileAsset[F]] =
    EntityDecoder[F, Multipart[F]]
      .flatMapR { multipart =>
        multipart.parts
          .flatMap { part =>
            for {
              fileName <- part.filename
              contentType <- part.contentType if contentType.mediaType.isImage
            } yield FileAsset[F](fileName, contentType.mediaType, part.body)
          }
          .headOption
          .fold[DecodeResult[F, FileAsset[F]]](
            EitherT.leftT(InvalidMessageBodyFailure("Unable to find a field with an image file"))
          ) { fileAsset =>
            // Only raster images are accepted: SVG can embed scripts that run when the file is served
            if (PlaylistService.AlbumArtMediaTypes.contains(fileAsset.mediaType)) EitherT.pure(fileAsset)
            else
              EitherT.leftT {
                InvalidMessageBodyFailure(
                  s"Unsupported image type: ${fileAsset.mediaType.mainType}/${fileAsset.mediaType.subType}"
                )
              }
          }
      }
}
