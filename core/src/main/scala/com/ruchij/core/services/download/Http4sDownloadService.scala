package com.ruchij.core.services.download

import cats.effect.Resource
import cats.effect.kernel.Temporal
import cats.implicits._
import com.ruchij.core.exceptions.ExternalServiceException
import com.ruchij.core.services.download.Http4sDownloadService.ResumeMetadata
import com.ruchij.core.services.download.models.DownloadResult
import com.ruchij.core.services.repository.RepositoryService
import com.ruchij.core.utils.Http4sUtils
import fs2.{Pipe, Stream, text}
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import io.circe.parser.decode
import io.circe.syntax._
import org.http4s.client.Client
import org.http4s.headers.{ETag, Range, `Content-Length`, `Content-Range`, `Content-Type`}
import org.http4s.{EntityTag, Header, Headers, RangeUnit, Request, Response, Status, Uri}
import org.typelevel.ci.CIString

class Http4sDownloadService[F[_]: Temporal](
  client: Client[F],
  repositoryService: RepositoryService[F]
) extends DownloadService[F] {

  override def download(uri: Uri, fileKey: String): Resource[F, DownloadResult[F]] = {
    val metadataKey = s"$fileKey.http-download.json"

    Resource.eval {
      for {
        initialSize <- repositoryService.size(fileKey).map(_.getOrElse(0L))
        metadata <- readMetadata(metadataKey)
        resume = metadata.filter { value =>
          value.uri == uri.renderString && initialSize > 0 && initialSize < value.size &&
            ETag.parse(value.etag).exists(_.tag.weakness == EntityTag.Strong)
        }
      } yield (initialSize, resume)
    }.flatMap { case (initialSize, resume) =>
      val headers = resume.fold(Headers.empty) { metadata =>
        Headers(Range(initialSize), Header.Raw(CIString("If-Range"), metadata.etag))
      }.put(Header.Raw(CIString("Accept-Encoding"), "identity"))

      client.run(Request[F](uri = uri, headers = headers)).evalMap { response =>
        validateResponse(uri, response, initialSize, resume).map { case (start, size, contentLength, mediaType) =>
          val metadata = response.headers.get[ETag]
            .filter(_.tag.weakness == EntityTag.Strong)
            .map(etag => ResumeMetadata(uri.renderString, etag.tag.toString, size))

          // Delay replacing the old file until the caller consumes the download. A partial response keeps its
          // original metadata; a full response starts a new representation, even after If-Range.
          val prepare =
            if (start > 0) Temporal[F].unit
            else
              repositoryService.delete(metadataKey).void *>
                repositoryService.delete(fileKey).void *>
                metadata.traverse_ { value =>
                  repositoryService.write(metadataKey, Stream.emit(value.asJson.noSpaces).through(text.utf8.encode))
                    .compile.drain
                }

          DownloadResult.create[F](uri, fileKey, size, mediaType) {
            Stream.eval(prepare).drain ++
              response.body
                .through(checkLength(uri, contentLength))
                .observe(data => repositoryService.write(fileKey, data))
                .chunks
                .scan(start) { case (total, chunk) => total + chunk.size } ++
              Stream.eval(repositoryService.delete(metadataKey)).drain
          }
        }
      }
    }
  }

  private def readMetadata(key: String): F[Option[ResumeMetadata]] =
    repositoryService.read(key, None, None).flatMap {
      case None => Temporal[F].pure(None)
      case Some(data) =>
        // Invalid or oversized metadata cannot authorize an append; restart with a full request instead.
        data.take(16385).compile.toVector.map { bytes =>
          if (bytes.size > 16384) None
          else decode[ResumeMetadata](new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)).toOption
        }
    }

  private def validateResponse(
    uri: Uri,
    response: Response[F],
    initialSize: Long,
    resume: Option[ResumeMetadata]
  ): F[(Long, Long, Long, org.http4s.MediaType)] = {
    val result = for {
      _ <- requireValid(
        response.status == Status.Ok || (response.status == Status.PartialContent && resume.nonEmpty),
        s"Unexpected download status: ${response.status}"
      )
      _ <- requireValid(
        response.headers.headers.filter(_.name == CIString("Content-Encoding"))
          .forall(_.value.equalsIgnoreCase("identity")),
        "Encoded download bodies are not supported"
      )
      contentLength <- Http4sUtils.header[F, `Content-Length`].run(response).map(_.length)
      contentType <- Http4sUtils.header[F, `Content-Type`].run(response).map(_.mediaType)
      start = if (response.status == Status.PartialContent) initialSize else 0L
      size <-
        if (response.status == Status.Ok) Temporal[F].pure(contentLength)
        else
          for {
            range <- Http4sUtils.header[F, `Content-Range`].run(response)
            expectedSize = resume.map(_.size).getOrElse(0L)
            _ <- requireValid(
              range.unit == RangeUnit.Bytes && range.range.first == start &&
                range.range.second.contains(expectedSize - 1) && range.length.contains(expectedSize) &&
                contentLength == expectedSize - start,
              "Content-Range does not match the requested remainder"
            )
            _ <- requireValid(
              response.headers.headers.filter(_.name == CIString("ETag")).forall { header =>
                ETag.parse(header.value).exists(etag => resume.exists(_.etag == etag.tag.toString))
              },
              "ETag changed or is invalid in a partial response"
            )
          } yield expectedSize
    } yield (start, size, contentLength, contentType)

    result.adaptError { case ExternalServiceException(error) =>
      ExternalServiceException(s"Download uri = $uri. $error")
    }
  }

  private def requireValid(condition: Boolean, message: String): F[Unit] =
    if (condition) Temporal[F].unit else Temporal[F].raiseError(ExternalServiceException(message))

  private def checkLength(uri: Uri, expected: Long): Pipe[F, Byte, Byte] = input =>
    Stream.eval(cats.effect.Ref.of[F, Long](0L)).flatMap { received =>
      input.chunks.evalMap { chunk =>
        received.get.flatMap { count =>
          requireValid(chunk.size.toLong <= expected - count, s"Download uri = $uri. Body exceeds Content-Length") *>
            received.set(count + chunk.size).as(chunk)
        }
      }.unchunks ++ Stream.eval {
        received.get.flatMap { count =>
          requireValid(count == expected, s"Download uri = $uri. Expected $expected bytes but received $count")
        }
      }.drain
    }
}

object Http4sDownloadService {
  private final case class ResumeMetadata(uri: String, etag: String, size: Long)

  private object ResumeMetadata {
    implicit val codec: Codec[ResumeMetadata] = deriveCodec[ResumeMetadata]
  }
}
