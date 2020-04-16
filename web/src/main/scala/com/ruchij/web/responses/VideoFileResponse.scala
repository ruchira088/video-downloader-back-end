package com.ruchij.web.responses

import cats.implicits._
import cats.{Functor, ~>}
import com.ruchij.daos.video.models.Video
import fs2.Stream
import org.http4s.headers.{Range, `Accept-Ranges`, `Content-Length`, `Content-Range`}
import org.http4s.{Headers, Response, Status}

case class VideoFileResponse[F[_]](video: Video, fileData: Stream[F, Byte], range: Option[Range.SubRange])

object VideoFileResponse {

  def response[F[_]: Functor](videoFileResponse: VideoFileResponse[F])(implicit functionK: Either[Throwable, *] ~> F): F[Response[F]] =
    functionK.apply(`Content-Length`.fromLong(videoFileResponse.video.videoMetadata.size))
      .map {
        contentLength =>
          Response()
            .withStatus(if (videoFileResponse.range.nonEmpty) Status.PartialContent else Status.Ok)
            .withHeaders(contentLength, `Accept-Ranges`.bytes)
            .withHeaders {
              videoFileResponse.range.fold(Headers.empty) { range =>
                Headers.of(`Content-Range`.apply(range, Some(contentLength.length)))
              }
            }
            .withBodyStream(videoFileResponse.fileData)
        }

  implicit class VideoFileResponseOps[F[_]: Functor: Either[Throwable, *] ~> *[_]](
    videoFileResponse: VideoFileResponse[F]
  ) {
    def asResponse: F[Response[F]] = response(videoFileResponse)
  }
}
