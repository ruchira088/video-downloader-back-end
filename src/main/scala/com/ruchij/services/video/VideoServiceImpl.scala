package com.ruchij.services.video

import cats.effect.Sync
import cats.implicits._
import cats.{Applicative, MonadError}
import com.ruchij.daos.scheduling.models.{VideoMetadata, VideoSite}
import com.ruchij.exceptions.ExternalServiceException
import org.http4s.client.Client
import org.http4s.headers.`Content-Length`
import org.http4s.{Method, Request, Uri}
import org.jsoup.Jsoup

class VideoServiceImpl[F[_]: Sync](client: Client[F]) extends VideoService[F] {

  override def metadata(uri: Uri): F[VideoMetadata] =
    for {
      videoSite <- VideoSite.infer[F](uri)

      html <- client.expect[String](uri)
      document <- Sync[F].delay(Jsoup.parse(html))

      videoTitle <- videoSite.title[F].apply(document)
      thumbnailUri <- videoSite.thumbnailUri[F].apply(document)
      duration <- videoSite.duration[F].apply(document)

      downloadUri <- videoSite.downloadUri[F].apply(document)
      size <- client.run(Request[F](Method.HEAD, downloadUri)).use { response =>
        response.headers
          .get(`Content-Length`)
          .fold[F[Long]](
            MonadError[F, Throwable]
              .raiseError(ExternalServiceException("""Response did not contain "Content-Length" header"""))
          ) { contentLength =>
            Applicative[F].pure(contentLength.length)
          }
      }

    } yield VideoMetadata(uri, videoSite, videoTitle, duration, size, thumbnailUri)
}
