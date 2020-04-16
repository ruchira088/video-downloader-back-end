package com.ruchij.web.responses

import cats.implicits._
import cats.{Functor, ~>}
import com.ruchij.daos.video.models.Video
import fs2.Stream
import org.http4s.headers.{Range, `Accept-Ranges`, `Content-Length`, `Content-Range`, `Content-Type`}
import org.http4s.{Headers, Response, Status}

case class VideoFileResponse[F[_]](video: Video, fileData: Stream[F, Byte], range: Option[Range.SubRange])

object VideoFileResponse {

  def response[F[_]: Functor](
    videoFileResponse: VideoFileResponse[F]
  )(implicit functionK: Either[Throwable, *] ~> F): F[Response[F]] =
    functionK
      .apply(
        `Content-Length`
          .fromLong(
            videoFileResponse.video.videoMetadata.size - videoFileResponse.range.map(_.first).getOrElse[Long](0)
          )
      )
      .map { contentLength =>
        Response()
          .withStatus(if (videoFileResponse.range.nonEmpty) Status.PartialContent else Status.Ok)
          .withHeaders {
            videoFileResponse.range.fold(Headers.empty) { range =>
              Headers.of(
                `Content-Range`.apply(
                  Range
                    .SubRange(range.first, range.second.orElse(Some(videoFileResponse.video.videoMetadata.size - 1))),
                  Some(videoFileResponse.video.videoMetadata.size)
                )
              )
            } ++
              Headers.of(
                contentLength,
                `Accept-Ranges`.bytes,
                `Content-Type`(videoFileResponse.video.videoMetadata.mediaType)
              )
          }
          .withBodyStream(videoFileResponse.fileData)
      }

  implicit class VideoFileResponseOps[F[_]: Functor: Either[Throwable, *] ~> *[_]](
    videoFileResponse: VideoFileResponse[F]
  ) {
    def asResponse: F[Response[F]] = response(videoFileResponse)
  }
}
