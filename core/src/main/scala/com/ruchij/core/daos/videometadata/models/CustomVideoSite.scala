package com.ruchij.core.daos.videometadata.models

import cats.data.{Kleisli, NonEmptyList}
import cats.implicits._
import cats.{Applicative, ApplicativeError, MonadThrow}
import com.ruchij.core.circe.Decoders.finiteDurationDecoder
import com.ruchij.core.daos.videometadata.models.CustomVideoSite.Selector
import com.ruchij.core.exceptions.ValidationException
import com.ruchij.core.services.renderer.SpaSiteRenderer
import com.ruchij.core.types.FunctionKTypes._
import com.ruchij.core.utils.JsoupSelector
import com.ruchij.core.utils.MatcherUtils.IntNumber
import enumeratum.{Enum, EnumEntry}
import org.http4s.circe.decodeUri
import org.http4s.implicits.http4sLiteralsSyntax
import org.http4s.{Query, Uri}
import io.circe.{parser => JsonParser}
import io.circe.generic.auto.exportDecoder

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.util.matching.Regex

sealed trait CustomVideoSite extends VideoSite with EnumEntry { self =>
  val hostname: String

  override val name: String = self.entryName

  def title[F[_]: MonadThrow]: Selector[F, String]

  def thumbnailUri[F[_]: MonadThrow]: Selector[F, Uri]

  def duration[F[_]: MonadThrow]: Selector[F, FiniteDuration]

  def test(uri: Uri): Boolean = uri.host.exists(_.value.toLowerCase.contains(hostname.toLowerCase))
}

object CustomVideoSite extends Enum[CustomVideoSite] {
  type Selector[F[_], A] = Kleisli[F, WebPage, A]

  private val VideoDurationFormatOne: Regex = "(\\d+):(\\d+)".r
  private val VideoDurationFormatTwo: Regex = "(\\d+):(\\d+):(\\d+)".r

  private def parseDuration[F[_]: ApplicativeError[*[_], Throwable]](duration: String): F[FiniteDuration] =
    duration match {
      case VideoDurationFormatTwo(IntNumber(hours), IntNumber(minutes), IntNumber(seconds)) =>
        Applicative[F].pure(FiniteDuration(hours * 3600 + minutes * 60 + seconds, TimeUnit.SECONDS))

      case VideoDurationFormatOne(IntNumber(minutes), IntNumber(seconds)) =>
        Applicative[F].pure(FiniteDuration(minutes * 60 + seconds, TimeUnit.SECONDS))

      case duration =>
        ApplicativeError[F, Throwable].raiseError {
          new IllegalArgumentException(s"""Unable to parse "$duration" as a duration""")
        }
    }

  sealed trait HtmlCustomVideoSite extends CustomVideoSite {
    def downloadUri[F[_]: MonadThrow]: Selector[F, Uri]
  }

  sealed trait SpaCustomVideoSite extends CustomVideoSite {
    private case class JsExecutionOutput private (videoUrl: String)

    protected def parseJsOutput[F[_]: MonadThrow](output: String): Kleisli[F, Uri, Uri] =
      Kleisli
        .liftF[F, Uri, JsExecutionOutput] {
          JsonParser.parse(output).flatMap(_.as[JsExecutionOutput]).toType[F, Throwable]
        }
        .flatMap {
          case JsExecutionOutput(videoPath) => JsoupSelector.stringToUri(videoPath)
        }

    val readyCssSelectors: Seq[String]

    def downloadUri[F[_]: MonadThrow](uri: Uri, spaSiteRenderer: SpaSiteRenderer[F]): F[Uri]
  }

  case object FreshPorno extends HtmlCustomVideoSite {
    override val hostname: String = "freshporno.net"

    override def downloadUri[F[_] : MonadThrow]: Selector[F, Uri] =
      JsoupSelector.nonEmptyElementList[F]("ul.download-list a")
        .map(_.head)
        .flatMapF(JsoupSelector.attribute[F](_, "href"))
        .flatMapF(urlString => Uri.fromString(urlString).toType[F, Throwable])

    override def title[F[_] : MonadThrow]: Selector[F, String] =
      JsoupSelector.singleElement[F](".video-info .title-holder h1")
        .flatMapF(JsoupSelector.text[F])

    override def thumbnailUri[F[_] : MonadThrow]: Selector[F, Uri] =
      JsoupSelector.singleElement[F](".player-wrap")
        .flatMapF(element => JsoupSelector.attribute[F](element, "data-test"))
        .flatMapF(urlString => Uri.fromString(urlString).toType[F, Throwable])

    override def duration[F[_] : MonadThrow]: Selector[F, FiniteDuration] =
      JsoupSelector.singleElement[F]("time")
        .flatMapF(JsoupSelector.text[F])
        .flatMapF(parseDuration[F])
  }

  case object PornOne extends HtmlCustomVideoSite {
    override val hostname: String = "pornone.com"

    private final case class PornOneMetadata(name: String, thumbnailUrl: List[Uri], duration: FiniteDuration)

    private def metadata[F[_]: MonadThrow]: Selector[F, PornOneMetadata] =
      JsoupSelector
        .singleElement[F]("script[data-react-helmet]")
        .map(_.data())
        .flatMapF(text => JsonParser.parse(text).toType[F, Throwable])
        .flatMapF(_.as[PornOneMetadata].toType[F, Throwable])

    override def title[F[_]: MonadThrow]: Selector[F, String] =
      metadata[F].map(_.name)

    override def thumbnailUri[F[_]: MonadThrow]: Selector[F, Uri] =
      metadata[F].flatMapF {
        pornOneMetadata =>
          pornOneMetadata.thumbnailUrl.headOption.toType[F, Throwable] {
            ValidationException("PornOne metadata .thumbnailUrl was empty")
          }
      }

    override def duration[F[_]: MonadThrow]: Selector[F, FiniteDuration] =
      metadata[F].map(_.duration)

    override def downloadUri[F[_]: MonadThrow]: Selector[F, Uri] =
      JsoupSelector
        .nonEmptyElementList[F]("#pornone-video-player source")
        .flatMap {
          case NonEmptyList(element, _) => JsoupSelector.src[F](element)
        }

    override def processUri[F[_]: MonadThrow](uri: Uri): F[Uri] =
      Applicative[F].pure(uri.copy(query = Query.empty))
  }

  case object SpankBang extends HtmlCustomVideoSite {
    override val hostname: String = "spankbang.com"

    override def title[F[_]: MonadThrow]: Selector[F, String] =
      JsoupSelector.extractText[F]("#video div.left h1[title]")

    override def thumbnailUri[F[_]: MonadThrow]: Selector[F, Uri] =
      JsoupSelector
        .singleElement[F]("#player_wrapper_outer div.play_cover img.player_thumb")
        .flatMap(JsoupSelector.src[F])

    override def duration[F[_]: MonadThrow]: Selector[F, FiniteDuration] =
      JsoupSelector
        .extractText[F]("#player_wrapper_outer .hd-time .i-length")
        .flatMapF(parseDuration[F])

    override def downloadUri[F[_]: MonadThrow]: Selector[F, Uri] =
      JsoupSelector
        .singleElement[F]("#video_container source")
        .flatMap(JsoupSelector.src[F])
  }

  case object XFreeHD extends HtmlCustomVideoSite {
    override val hostname: String = "www.xfreehd.com"

    override def title[F[_]: MonadThrow]: Selector[F, String] =
      JsoupSelector.extractText[F]("h1.big-title-truncate")

    override def thumbnailUri[F[_]: MonadThrow]: Selector[F, Uri] =
      videoPlayerAttributeUri[F]("data-img")

    override def duration[F[_]: MonadThrow]: Selector[F, FiniteDuration] =
      videoPlayerAttributeUri[F]("data-vtt")
        .map { uri =>
          uri.query.params
            .get("time")
            .flatMap(_.toIntOption)
            .map(seconds => FiniteDuration(seconds, TimeUnit.SECONDS))
            .getOrElse(FiniteDuration(0, TimeUnit.SECONDS))
        }

    override def downloadUri[F[_]: MonadThrow]: Selector[F, Uri] =
      JsoupSelector
        .nonEmptyElementList[F]("#hdPlayer source")
        .flatMap { elements =>
          JsoupSelector.src[F] {
            elements
              .find(element => Option(element.attr("title")).contains("HD"))
              .getOrElse(elements.last)
          }
        }

    private def videoPlayerAttributeUri[F[_]: MonadThrow](attributeName: String): Selector[F, Uri] =
      JsoupSelector
        .singleElement[F]("#hdPlayer")
        .flatMapF(videoElement => JsoupSelector.attribute[F](videoElement, attributeName))
        .flatMapF(uriString => Uri.fromString(uriString).toType[F, Throwable])
  }

  case object SxyPrn extends SpaCustomVideoSite {
    private val Duration = "[\\S|\\s]*duration:(\\S+).*".r

    override val hostname: String = "sxyprn.com"

    override val readyCssSelectors: Seq[String] = List("#player_el[src]")

    private val JavascriptCode =
      """
        const run =
          () => {
            const video = document.querySelector("#player_el[src]")
            const videoUrl = video.src

            return ({videoUrl})
          }

        run()
      """

    override def title[F[_]: MonadThrow]: Selector[F, String] =
      JsoupSelector
        .extractText[F](".post_el_wrap .post_text")

    override def thumbnailUri[F[_]: MonadThrow]: Selector[F, Uri] =
      JsoupSelector
        .singleElement[F]("#player_el")
        .flatMapF(JsoupSelector.attribute[F](_, "poster"))
        .flatMap(uriString => JsoupSelector.stringToUri[F](uriString).local[WebPage](_.uri))

    override def duration[F[_]: MonadThrow]: Selector[F, FiniteDuration] =
      JsoupSelector
        .extractText[F](".post_el_wrap")
        .flatMapF {
          case Duration(durationString) => parseDuration[F](durationString)
          case text =>
            ApplicativeError[F, Throwable].raiseError {
              new IllegalArgumentException(s"Unable to find $Duration in $text")
            }
        }

    override def downloadUri[F[_]: MonadThrow](uri: Uri, spaSiteRenderer: SpaSiteRenderer[F]): F[Uri] =
      spaSiteRenderer.executeJavaScript(uri, readyCssSelectors, JavascriptCode)
        .flatMap(output => parseJsOutput[F](output).run(uri))
  }

  sealed trait TxxxNetwork extends SpaCustomVideoSite {
    private case class TxxNetworkMetadata private (
      name: String,
      thumbnailUrl: Option[Uri],
      duration: Option[FiniteDuration]
    )

    private val JavascriptCode: String =
      """
        const run =
          () => {
            const playlist = window.jw_player?.getPlaylist()
            const videoUrl = playlist[0]?.sources[0]?.file

            return ({videoUrl})
          }

        run()
      """

    override val readyCssSelectors: Seq[String] =
      Seq(".jw-preview[style]", ".jw-text-duration", "video.jw-video[src]", "script[type='application/ld+json']")

    private val ThumbnailUrl: Regex = ".*background-image: url\\(\"(\\S+)\"\\);.*".r

    private def metadata[F[_]: MonadThrow]: Selector[F, TxxNetworkMetadata] =
      JsoupSelector
        .singleElement[F]("script[type='application/ld+json']")
        .map(_.data())
        .flatMapF { data =>
          JsonParser.parse(data).flatMap(_.as[TxxNetworkMetadata]).toType[F, Throwable]
        }

    override def title[F[_]: MonadThrow]: Selector[F, String] =
      metadata[F].map(_.name)

    override def thumbnailUri[F[_]: MonadThrow]: Selector[F, Uri] =
      metadata[F].map(_.thumbnailUrl).or {
        JsoupSelector
          .singleElement[F](".jw-preview")
          .map(_.attr("style"))
          .flatMapF {
            case ThumbnailUrl(imageUrl) => Uri.fromString(imageUrl).toType[F, Throwable]
            case _ =>
              Applicative[F].pure {
                uri"https://s3.ap-southeast-2.amazonaws.com/assets.video-downloader.ruchij.com/video-placeholder.png"
              }
          }
      }

    override def duration[F[_]: MonadThrow]: Selector[F, FiniteDuration] =
      metadata[F].map(_.duration).or {
        JsoupSelector
          .extractText[F](".jw-text-duration")
          .flatMapF(parseDuration[F])
      }

    override def downloadUri[F[_]: MonadThrow](uri: Uri, spaSiteRenderer: SpaSiteRenderer[F]): F[Uri] =
      spaSiteRenderer
        .executeJavaScript(uri, readyCssSelectors, JavascriptCode)
        .flatMap { output =>
          parseJsOutput[F](output).run(uri)
        }
        .map(_.removeQueryParam("f"))
  }

  case object TXXX extends TxxxNetwork {
    override val hostname: String = "txxx.com"
  }

  case object UPornia extends TxxxNetwork {
    override val hostname: String = "upornia.com"
  }

  case object HClips extends TxxxNetwork {
    override val hostname: String = "hclips.com"
  }

  case object HotMovs extends TxxxNetwork {
    override val hostname: String = "hotmovs.com"
  }

  case object HdZog extends TxxxNetwork {
    override val hostname: String = "hdzog.com"
  }

  override def values: IndexedSeq[CustomVideoSite] = findValues
}
