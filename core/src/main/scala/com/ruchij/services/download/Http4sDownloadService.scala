package com.ruchij.services.download

import cats.effect.{Clock, Concurrent, ContextShift, Resource}
import cats.implicits._
import com.ruchij.services.download.models.{DownloadResult, RangeFrom}
import com.ruchij.services.repository.RepositoryService
import com.ruchij.utils.Http4sUtils
import org.http4s.client.Client
import org.http4s.headers.{`Content-Length`, `Content-Type`}
import org.http4s.{Headers, Request, Uri}

class Http4sDownloadService[F[_]: Concurrent: ContextShift: Clock](
  client: Client[F],
  repositoryService: RepositoryService[F]
) extends DownloadService[F] {

  override def download(uri: Uri, fileKey: String): Resource[F, DownloadResult[F]] =
    Resource
      .liftF { repositoryService.size(fileKey).map(_.getOrElse(0L)) }
      .flatMap { start =>
        client
          .run(Request(uri = uri, headers = Headers.of(RangeFrom(start))))
          .map(_ -> start)
      }
      .evalMap {
        case (response, start) =>
          Http4sUtils
            .header[F](`Content-Length`)
            .product(Http4sUtils.header[F](`Content-Type`))
            .map {
              case (contentLengthValue, contentTypeValue) =>
                (contentLengthValue.length, contentTypeValue.mediaType)
            }
            .run(response)
            .map {
              case (fileSize, mediaType) =>
                DownloadResult.create[F](uri, fileKey, fileSize + start, mediaType) {
                  response.body
                    .observe { data =>
                      repositoryService.write(fileKey, data)
                    }
                    .chunks
                    .scan(start) { case (total, chunk) => total + chunk.size }
                }
            }
      }

}