package com.ruchij.api.web.requests

import cats.data.EitherT
import cats.effect.kernel.Async
import com.ruchij.api.services.playlist.PlaylistService
import com.ruchij.api.utils.StreamUtils
import com.ruchij.core.exceptions.ValidationException
import fs2.Stream
import org.http4s.multipart.Multipart
import org.http4s.{DecodeResult, EntityDecoder, InvalidMessageBodyFailure, Media, MediaRange, MediaType}

final case class FileAsset[F[_]](fileName: String, mediaType: MediaType, data: Stream[F, Byte])

object FileAsset {

  /** The largest multipart request body accepted: the largest album art, plus room for the boundaries and headers */
  val MaxRequestBodySize: Long = PlaylistService.MaxAlbumArtSize + 64 * 1024

  implicit def fileAssetDecoder[F[_]: Async]: EntityDecoder[F, FileAsset[F]] = decoder[F](MaxRequestBodySize)

  /**
    * http4s' default multipart decoder buffers every part in memory, so the size of the body is limited before it is
    * decoded: a body with a Content-Length over maxBodySize is rejected without being read, and any other body fails
    * as soon as more than maxBodySize bytes of it have been read.
    */
  def decoder[F[_]: Async](maxBodySize: Long): EntityDecoder[F, FileAsset[F]] = {
    val multipartDecoder: EntityDecoder[F, FileAsset[F]] = fromMultipart[F]

    new EntityDecoder[F, FileAsset[F]] {
      override def decode(media: Media[F], strict: Boolean): DecodeResult[F, FileAsset[F]] =
        if (media.contentLength.exists(_ > maxBodySize))
          EitherT.liftF(Async[F].raiseError[FileAsset[F]](bodyTooLarge(maxBodySize)))
        else
          multipartDecoder.decode(
            Media[F](media.body.through(StreamUtils.limitSize[F](maxBodySize)(bodyTooLarge(maxBodySize))), media.headers),
            strict
          )

      override def consumes: Set[MediaRange] = multipartDecoder.consumes
    }
  }

  private def bodyTooLarge(maxBodySize: Long): ValidationException =
    ValidationException(s"Request body exceeds the maximum size of $maxBodySize bytes")

  private def fromMultipart[F[_]: Async]: EntityDecoder[F, FileAsset[F]] =
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
