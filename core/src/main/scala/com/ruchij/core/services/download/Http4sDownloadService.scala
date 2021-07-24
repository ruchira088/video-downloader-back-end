package com.ruchij.core.services.download

import cats.ApplicativeError
import cats.effect.{Clock, Concurrent, ContextShift, Resource}
import cats.implicits._
import com.ruchij.core.exceptions.ExternalServiceException
import com.ruchij.core.services.download.Http4sDownloadService.MinChunkUpdateSize
import com.ruchij.core.services.download.models.DownloadResult
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.utils.Http4sUtils
import org.http4s.client.Client
import org.http4s.headers.{Range, `Content-Length`, `Content-Type`}
import org.http4s.{Headers, Request, Uri}

class Http4sDownloadService[F[_]: Concurrent: ContextShift: Clock](
  client: Client[F],
  repositoryService: RepositoryService[F]
) extends DownloadService[F] {

  override def download(uri: Uri, fileKey: String): Resource[F, DownloadResult[F]] =
    Resource
      .eval { repositoryService.size(fileKey).map(_.getOrElse(0L)) }
      .flatMap { start =>
        client
          .run(Request(uri = uri, headers = Headers(Range(start))))
          .map(_ -> start)
      }
      .evalMap {
        case (response, initialSize) =>
          ApplicativeError[F, Throwable]
            .recoverWith {
              Http4sUtils
                .header[F, `Content-Length`]
                .product(Http4sUtils.header[F, `Content-Type`])
                .map {
                  case (contentLengthValue, contentTypeValue) =>
                    (contentLengthValue.length, contentTypeValue.mediaType)
                }
                .run(response)
            } {
              case ExternalServiceException(error) =>
                ApplicativeError[F, Throwable].raiseError {
                  ExternalServiceException(s"Download uri = $uri. $error")
                }
            }
            .map {
              case (contentLength, mediaType) =>
                DownloadResult.create[F](uri, fileKey, contentLength + initialSize, mediaType) {
                  response.body
                    .observe { data =>
                      repositoryService.write(fileKey, data)
                    }
                    .chunkMin(MinChunkUpdateSize)
                    .scan(initialSize) { case (total, chunk) => total + chunk.size }
                }
            }
      }

}

object Http4sDownloadService {
  val MinChunkUpdateSize: Int = 100 * 1000 // 100KB
}
