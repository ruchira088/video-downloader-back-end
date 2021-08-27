package com.ruchij.core.daos.videometadata.models

import com.ruchij.core.exceptions.ValidationException
import org.http4s.Uri

trait VideoSite {
  val name: String
}

object VideoSite {
  def from(input: String): VideoSite =
    CustomVideoSite.withNameInsensitiveOption(input)
      .getOrElse {
        if (Local.name.equalsIgnoreCase(input)) Local else YTDownloaderSite(input)
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
      .fold[Either[ValidationException, VideoSite]](Left(ValidationException(s"Unable infer video site from ${uri.renderString}"))) {
        videoSite => Right(videoSite)
      }

  case object Local extends VideoSite {
    override val name: String = "Local"
  }

  case class YTDownloaderSite(site: String) extends VideoSite {
    override val name: String = site
  }
}
