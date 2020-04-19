package com.ruchij.services.download

import java.util.concurrent.TimeUnit

import cats.effect.{Clock, Concurrent, ContextShift, Resource}
import cats.implicits._
import cats.{Applicative, MonadError}
import com.ruchij.services.download.models.DownloadResult
import com.ruchij.services.repository.RepositoryService
import com.ruchij.utils.Http4sUtils
import org.http4s.client.Client
import org.http4s.headers.`Content-Length`
import org.http4s.{Request, Uri}

class Http4sDownloadService[F[_]: Concurrent: ContextShift: Clock](
  client: Client[F],
  repositoryService: RepositoryService[F]
) extends DownloadService[F] {

  override def download(uri: Uri, parent: String): Resource[F, DownloadResult[F]] =
    client
      .run(Request(uri = uri))
      .product {
        Resource.liftF {
          uri.path
            .split("/")
            .lastOption
            .fold[F[String]](Clock[F].realTime(TimeUnit.MILLISECONDS).map(_.toString)) {
              suffix => Applicative[F].pure(suffix) }
            .map { fileName =>
              (parent + "/" + fileName).split("/").filter(_.trim.nonEmpty).mkString("/")
            }
        }
      }
      .evalMap {
        case (response, key) =>
          Http4sUtils.header[F](`Content-Length`).map(_.length).run(response)
            .map { fileSize =>
              DownloadResult.create[F](key, fileSize) {
                response.body
                  .observe { data =>
                    repositoryService.write(key, data)
                  }
                  .chunks
                  .scan(0L) { case (total, chunk) => total + chunk.size }
              }
            }
      }

}
