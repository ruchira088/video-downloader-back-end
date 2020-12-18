package com.ruchij.core.daos.videometadata.models

import java.util.concurrent.TimeUnit
import cats.data.{Kleisli, NonEmptyList}
import cats.{Applicative, ApplicativeError, MonadError}
import com.ruchij.core.circe.Decoders.finiteDurationDecoder
import com.ruchij.core.daos.videometadata.models.VideoSite.Selector
import com.ruchij.core.exceptions.InvalidConditionException
import com.ruchij.core.types.FunctionKTypes
import com.ruchij.core.utils.JsoupSelector
import com.ruchij.core.utils.MatcherUtils.IntNumber
import enumeratum.{Enum, EnumEntry}
import io.circe.{Decoder, parser => JsonParser}
import io.circe.generic.auto.exportDecoder
import org.http4s.Uri
import org.jsoup.nodes.Document

import scala.concurrent.duration.FiniteDuration
import scala.util.matching.Regex

sealed trait VideoSite extends EnumEntry {
  val hostname: String

  def title[F[_]: MonadError[*[_], Throwable]]: Selector[F, String]

  def thumbnailUri[F[_]: MonadError[*[_], Throwable]]: Selector[F, Uri]

  def duration[F[_]: MonadError[*[_], Throwable]]: Selector[F, FiniteDuration]

  def downloadUri[F[_]: MonadError[*[_], Throwable]]: Selector[F, Uri]

  def test(uri: Uri): Boolean = uri.host.exists(_.value.toLowerCase.contains(hostname.toLowerCase))
}

object VideoSite extends Enum[VideoSite] {

  type Selector[F[_], A] = Kleisli[F, Document, A]

  def notApplicable[F[_]: ApplicativeError[*[_], Throwable], A]: Kleisli[F, Document, A] =
    Kleisli.liftF[F, Document, A] {
      ApplicativeError[F, Throwable].raiseError[A] {
        InvalidConditionException {
          "Unable to gather site metadata for local video"
        }
      }
    }

  case object PornOne extends VideoSite {
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
          FunctionKTypes.eitherToF[Throwable, F].apply(Uri.fromString(urlString))
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
  }

  case object SpankBang extends VideoSite {
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

  case object EPorner extends VideoSite {

    case class EPornerMetadata(name: String, duration: FiniteDuration, image: Uri)

    implicit val uriDecoder: Decoder[Uri] =
      Decoder.decodeString.emap { uriString => Uri.fromString(uriString).left.map(_.message) }

    def metadata[F[_]: MonadError[*[_], Throwable]]: Selector[F, EPornerMetadata] =
      JsoupSelector.singleElement("""#movieplayer-left [type="application/ld+json"]""")
        .map(_.html())
        .flatMapF { text =>
          FunctionKTypes.eitherToF.apply {
            for {
              json <- JsonParser.parse(text)
              metadata <- json.as[EPornerMetadata]
            }
            yield metadata
          }
        }

    override val hostname: String = "eporner.com"

    override def title[F[_] : MonadError[*[_], Throwable]]: Selector[F, String] =
      metadata.map(_.name)

    override def thumbnailUri[F[_] : MonadError[*[_], Throwable]]: Selector[F, Uri] =
      metadata.map(_.image)

    override def duration[F[_] : MonadError[*[_], Throwable]]: Selector[F, FiniteDuration] =
      metadata.map(_.duration)

    override def downloadUri[F[_] : MonadError[*[_], Throwable]]: Selector[F, Uri] =
      JsoupSelector.nonEmptyElementList("#hd-porn-dload a")
        .map(_.reverse)
        .flatMapF {
          case NonEmptyList(head, _) => JsoupSelector.attribute(head, "href")
        }
        .flatMapF {
          path => FunctionKTypes.eitherToF.apply(Uri.fromString(s"https://www.eporner.com$path"))
        }
  }

  case object Local extends VideoSite {
    override val hostname: String = "localhost"

    override def title[F[_] : MonadError[*[_], Throwable]]: Selector[F, String] = notApplicable

    override def thumbnailUri[F[_] : MonadError[*[_], Throwable]]: Selector[F, Uri] = notApplicable

    override def duration[F[_] : MonadError[*[_], Throwable]]: Selector[F, FiniteDuration] = notApplicable

    override def downloadUri[F[_] : MonadError[*[_], Throwable]]: Selector[F, Uri] = notApplicable
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
