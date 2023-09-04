package com.ruchij.core.daos.videometadata.models

import cats.implicits.toFlatMapOps
import cats.{Applicative, ApplicativeError, MonadThrow}
import com.ruchij.core.exceptions.ValidationException
import com.ruchij.core.types.FunctionKTypes._
import org.http4s.Uri

trait VideoSite {
  val name: String

  def processUri[F[_]: MonadThrow](uri: Uri): F[Uri] = Applicative[F].pure(uri)
}

object VideoSite {
  def from(input: String): VideoSite =
    CustomVideoSite.withNameInsensitiveOption(input)
      .getOrElse {
        if (Local.name.equalsIgnoreCase(input)) Local else YTDownloaderSite(input.toLowerCase)
      }

  def fromUri(uri: Uri): Either[ValidationException, VideoSite] =
    CustomVideoSite.values
      .find(_.test(uri))
      .orElse {
        uri.host
          .map { host =>
            host.renderString.split('.').reverse.toList match {
              case _ :: name :: _ => YTDownloaderSite(name)
              case _ => YTDownloaderSite(host.renderString)
            }
          }
      }
      .toRight { ValidationException(s"Unable infer video site from ${uri.renderString}") }

  def processUri[F[_]: MonadThrow](uri: Uri): F[Uri] = fromUri(uri).toType[F, Throwable].flatMap(_.processUri(uri))

  case object Local extends VideoSite {
    override val name: String = "Local"

    override def processUri[F[_] : MonadThrow](uri: Uri): F[Uri] =
      ApplicativeError[F, Throwable].raiseError(ValidationException("Unable to process URIs for local videos"))
  }

  final case class YTDownloaderSite(site: String) extends VideoSite {
    override val name: String = site

    override def processUri[F[_] : MonadThrow](uri: Uri): F[Uri] =
      uri.host.map(_.toString()) match {
        case Some("www.youtube.com") =>
          Applicative[F].pure {
            uri.copy(query = uri.query.filter { case (key, _) => key == "v" })
          }

        case _ => Applicative[F].pure(uri)
      }
  }
}
