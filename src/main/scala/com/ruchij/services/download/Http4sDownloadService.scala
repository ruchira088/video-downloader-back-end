package com.ruchij.services.download

import java.nio.file.Path

import cats.MonadError
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync}
import cats.implicits._
import com.ruchij.config.DownloadConfiguration
import com.ruchij.exceptions.ExternalServiceException
import com.ruchij.services.download.models.DownloadResult
import fs2.io.file.writeAll
import org.http4s.client.Client
import org.http4s.{Request, Uri}

class Http4sDownloadService[F[_]: Concurrent: ContextShift](
  client: Client[F],
  blocker: Blocker,
  downloadConfiguration: DownloadConfiguration
) extends DownloadService[F] {

  override def download(uri: Uri): Resource[F, DownloadResult[F]] =
    client.run(Request(uri = uri))
        .evalMap { response =>
          uri.path
            .split("/")
            .lastOption
            .fold[F[Path]](
              MonadError[F, Throwable].raiseError(new IllegalArgumentException("Download uri cannot be empty"))
            ) { fileName =>
              Sync[F].delay(downloadConfiguration.homeFolder.resolve(fileName))
            }
            .flatMap { destination =>
              response.headers.get(org.http4s.headers.`Content-Length`).map(_.length)
                .fold[F[Long]](Sync[F].raiseError(ExternalServiceException("""Response did not contain the "Content-Length" header"""))) {
                  length => Sync[F].delay(length)
                }
                .map { fileSize =>
                  DownloadResult.create[F](destination, fileSize) {
                    response.body
                      .observe(writeAll[F](destination, blocker))
                      .chunks
                      .scan(0L) { case (total, chunk) => total + chunk.size }
                  }
                }
            }
        }
}
