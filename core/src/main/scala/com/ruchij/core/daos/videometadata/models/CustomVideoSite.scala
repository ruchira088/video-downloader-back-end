package com.ruchij.core.daos.videometadata.models

import cats.data.{Kleisli, NonEmptyList}
import cats.{Applicative, ApplicativeError, MonadError}
import com.ruchij.core.daos.videometadata.models.CustomVideoSite.Selector
import com.ruchij.core.exceptions.InvalidConditionException
import com.ruchij.core.types.FunctionKTypes.{FunctionK2TypeOps, eitherToF}
import com.ruchij.core.utils.JsoupSelector
import com.ruchij.core.utils.MatcherUtils.IntNumber
import enumeratum.{Enum, EnumEntry}
import org.http4s.{Query, Uri}
import org.jsoup.nodes.Document

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.util.matching.Regex

sealed trait CustomVideoSite extends VideoSite with EnumEntry { self =>
  val hostname: String

  override val name: String = self.entryName

  def title[F[_]: MonadError[*[_], Throwable]]: Selector[F, String]

  def thumbnailUri[F[_]: MonadError[*[_], Throwable]]: Selector[F, Uri]

  def duration[F[_]: MonadError[*[_], Throwable]]: Selector[F, FiniteDuration]

  def downloadUri[F[_]: MonadError[*[_], Throwable]]: Selector[F, Uri]

  def processUri[F[_]: MonadError[*[_],Throwable]](uri: Uri): F[Uri] = Applicative[F].pure(uri)

  def test(uri: Uri): Boolean = uri.host.exists(_.value.toLowerCase.contains(hostname.toLowerCase))
}

object CustomVideoSite extends Enum[CustomVideoSite] {

  type Selector[F[_], A] = Kleisli[F, Document, A]

  def notApplicable[F[_]: ApplicativeError[*[_], Throwable], A]: Kleisli[F, Document, A] =
    Kleisli.liftF[F, Document, A] {
      ApplicativeError[F, Throwable].raiseError[A] {
        InvalidConditionException {
          "Unable to gather site metadata for local video"
        }
      }
    }

  case object PornOne extends CustomVideoSite {
    override val hostname: String = "pornone.com"

    private val LessThanHour = "(\\d+) min (\\d+) sec".r
    private val MoreThanHour = "(\\d+) hours (\\d+) min (\\d+) sec".r

    override def title[F[_]: MonadError[*[_], Throwable]]: Selector[F, String] =
      JsoupSelector.singleElement[F](".single-video .video-player-head h1")
        .flatMapF(JsoupSelector.text[F])

    override def thumbnailUri[F[_]: MonadError[*[_], Throwable]]: Selector[F, Uri] =
      JsoupSelector
        .singleElement[F]("#video_player video")
        .flatMapF(element => JsoupSelector.attribute[F](element, "poster"))
        .flatMapF { urlString =>
          Uri.fromString(urlString).toType[F, Throwable]
        }

    override def duration[F[_]: MonadError[*[_], Throwable]]: Selector[F, FiniteDuration] =
      JsoupSelector
        .singleElement[F]("#video-info .video-duration")
        .flatMapF(JsoupSelector.text[F])
        .flatMapF {
          case LessThanHour(IntNumber(minutes), IntNumber(seconds)) =>
            Applicative[F].pure(FiniteDuration(minutes * 60 + seconds, TimeUnit.SECONDS))

          case MoreThanHour(IntNumber(hours), IntNumber(minutes), IntNumber(seconds)) =>
            Applicative[F].pure(FiniteDuration(hours * 3600 + minutes * 60 + seconds, TimeUnit.SECONDS))

          case duration =>
            ApplicativeError[F, Throwable].raiseError {
              new IllegalArgumentException(s"""Unable to parse "$duration" as a duration""")
            }
        }

    override def downloadUri[F[_]: MonadError[*[_], Throwable]]: Selector[F, Uri] =
      JsoupSelector
        .nonEmptyElementList[F]("#video_player source")
        .flatMapF {
          case NonEmptyList(element, _) => JsoupSelector.src[F](element)
        }

    override def processUri[F[_] : MonadError[*[_], Throwable]](uri: Uri): F[Uri] =
      Applicative[F].pure(uri.copy(query = Query.empty))
  }

  case object SpankBang extends CustomVideoSite {
    override val hostname: String = "spankbang.com"

    private val VideoDuration: Regex = "(\\d+):(\\d+)".r

    override def title[F[_] : MonadError[*[_], Throwable]]: Selector[F, String] =
      JsoupSelector.singleElement[F]("#video div.left h1[title]")
        .flatMapF(JsoupSelector.text[F])

    override def thumbnailUri[F[_] : MonadError[*[_], Throwable]]: Selector[F, Uri] =
      JsoupSelector.singleElement[F]("#player_wrapper_outer div.play_cover img.player_thumb")
        .flatMapF(JsoupSelector.src[F])

    override def duration[F[_] : MonadError[*[_], Throwable]]: Selector[F, FiniteDuration] =
      JsoupSelector.singleElement[F]("#player_wrapper_outer .hd-time .i-length")
        .flatMapF(JsoupSelector.text[F])
        .flatMapF {
          case VideoDuration(IntNumber(minutes), IntNumber(seconds)) =>
            Applicative[F].pure(FiniteDuration(minutes * 60 + seconds, TimeUnit.SECONDS))

          case duration =>
            ApplicativeError[F, Throwable].raiseError {
              new IllegalArgumentException(s"""Unable to parse "$duration" as a duration""")
            }
        }

    override def downloadUri[F[_] : MonadError[*[_], Throwable]]: Selector[F, Uri] =
      JsoupSelector.singleElement[F]("#video_container source")
        .flatMapF(JsoupSelector.src[F])
  }

  override def values: IndexedSeq[CustomVideoSite] = findValues
}
