package com.ruchij.api.web.requests

import cats.data.EitherT
import cats.effect.kernel.Async
import fs2.Stream
import org.http4s.multipart.Multipart
import org.http4s.{DecodeResult, EntityDecoder, InvalidMessageBodyFailure, MediaType}

case class FileAsset[F[_]](fileName: String, mediaType: MediaType, data: Stream[F, Byte])

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
            EitherT.pure(fileAsset)
          }
      }
}
