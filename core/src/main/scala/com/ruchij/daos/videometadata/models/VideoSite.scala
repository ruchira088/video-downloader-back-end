package com.ruchij.daos.videometadata.models

import java.util.concurrent.TimeUnit

import cats.data.Kleisli
import cats.{Applicative, MonadError}
import com.ruchij.daos.videometadata.models.VideoSite.Selector
import com.ruchij.types.FunctionKTypes
import com.ruchij.utils.JsoupUtils
import com.ruchij.utils.MatcherUtils.IntNumber
import enumeratum.{Enum, EnumEntry}
import org.http4s.Uri
import org.jsoup.nodes.Document

import scala.concurrent.duration.FiniteDuration

sealed trait VideoSite extends EnumEntry {
  val HOSTNAME: String

  def title[F[_]: MonadError[*[_], Throwable]]: Selector[F, String]

  def thumbnailUri[F[_]: MonadError[*[_], Throwable]]: Selector[F, Uri]

  def duration[F[_]: MonadError[*[_], Throwable]]: Selector[F, FiniteDuration]

  def downloadUri[F[_]: MonadError[*[_], Throwable]]: Selector[F, Uri]

  def test(uri: Uri): Boolean = uri.host.exists(hostname => hostname.value.toLowerCase.contains(HOSTNAME.toLowerCase))
}

object VideoSite extends Enum[VideoSite] {

  type Selector[F[_], A] = Kleisli[F, Document, A]

  case object VPorn extends VideoSite {
    override val HOSTNAME: String = "vporn.com"

    override def title[F[_]: MonadError[*[_], Throwable]]: Selector[F, String] =
      JsoupUtils.query[F](".single-video .video-player-head h1").map(_.text())

    override def thumbnailUri[F[_]: MonadError[*[_], Throwable]]: Selector[F, Uri] =
      JsoupUtils
        .query[F]("#video_player video")
        .map(_.attr("poster"))
        .flatMapF { urlString =>
          FunctionKTypes.eitherToF[Throwable, F].apply(Uri.fromString(urlString))
        }

    private val lessThanHour = "(\\d+) min (\\d+) sec".r
    private val moreThanHour = "(\\d+) hours (\\d+) min (\\d+) sec".r

    override def duration[F[_]: MonadError[*[_], Throwable]]: Selector[F, FiniteDuration] =
      JsoupUtils
        .query[F]("#video-info .video-duration")
        .map(_.text().trim)
        .flatMapF {
          case lessThanHour(IntNumber(minutes), IntNumber(seconds)) =>
            Applicative[F].pure(FiniteDuration(minutes * 60 + seconds, TimeUnit.SECONDS))

          case moreThanHour(IntNumber(hours), IntNumber(minutes), IntNumber(seconds)) =>
            Applicative[F].pure(FiniteDuration(hours * 3600 + minutes * 60 + seconds, TimeUnit.SECONDS))

          case duration =>
            MonadError[F, Throwable].raiseError {
              new IllegalArgumentException(s"""Unable to parse "$duration" as a duration""")
            }
        }

    override def downloadUri[F[_]: MonadError[*[_], Throwable]]: Selector[F, Uri] =
      JsoupUtils
        .query[F]("#video_player source")
        .flatMapF { element =>
          FunctionKTypes.eitherToF[Throwable, F].apply(Uri.fromString(element.attr("src")))
        }
  }

  override def values: IndexedSeq[VideoSite] = findValues

  def infer[F[_]: MonadError[*[_], Throwable]](uri: Uri): F[VideoSite] =
    values
      .find(_.test(uri))
      .fold[F[VideoSite]](
        MonadError[F, Throwable].raiseError(new IllegalArgumentException(s"""Unable to infer video site from "$uri""""))
      ) { videoSite =>
        Applicative[F].pure(videoSite)
      }
}
