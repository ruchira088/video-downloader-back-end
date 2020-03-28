package com.ruchij.services.download

import java.nio.file.Path

import cats.MonadError
import cats.effect.{Blocker, Concurrent, ContextShift, Resource, Sync}
import cats.implicits._
import com.ruchij.services.download.models.DownloadResult
import com.ruchij.utils.Http4sUtils
import fs2.io.file.writeAll
import org.http4s.client.Client
import org.http4s.{Request, Uri}

class Http4sDownloadService[F[_]: Concurrent: ContextShift](client: Client[F], blocker: Blocker)
    extends DownloadService[F] {

  override def download(uri: Uri, folder: Path): Resource[F, DownloadResult[F]] =
    client
      .run(Request(uri = uri))
      .evalMap { response =>
        uri.path
          .split("/")
          .lastOption
          .fold[F[Path]](
            MonadError[F, Throwable].raiseError(new IllegalArgumentException("Download uri cannot be empty"))
          ) { fileName =>
            Sync[F].delay(folder.resolve(fileName))
          }
          .flatMap { destination =>
            Http4sUtils
              .contentLength[F](response)
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
