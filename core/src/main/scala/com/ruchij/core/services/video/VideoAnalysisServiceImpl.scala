package com.ruchij.core.services.video

import cats.data.Kleisli
import cats.effect.Sync
import cats.implicits._
import com.ruchij.core.daos.videometadata.models.VideoSite
import com.ruchij.core.daos.videometadata.models.VideoSite.Selector
import com.ruchij.core.services.video.models.VideoAnalysisResult
import com.ruchij.core.utils.Http4sUtils
import org.http4s.client.Client
import org.http4s.headers.`Content-Length`
import org.http4s.{Method, Request, Uri}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class VideoAnalysisServiceImpl[F[_]: Sync](client: Client[F])
    extends VideoAnalysisService[F] {

  override def metadata(uri: Uri): F[VideoAnalysisResult] =
    for {
      (videoSite, document) <- uriInfo(uri)
      videoAnalysisResult <- analyze(uri, videoSite).run(document)
    }
    yield videoAnalysisResult

  override def downloadUri(uri: Uri): F[Uri] =
    for {
      (videoSite, document) <- uriInfo(uri)
      downloadUri <- videoSite.downloadUri[F].run(document)
    } yield downloadUri

  def uriInfo(uri: Uri): F[(VideoSite, Document)] =
    for {
      videoSite <- VideoSite.infer[F](uri)

      html <- client.expect[String](uri)
      document <- Sync[F].catchNonFatal(Jsoup.parse(html))
    } yield (videoSite, document)

  def analyze(uri: Uri, videoSite: VideoSite): Selector[F, VideoAnalysisResult] =
    for {
      videoTitle <- videoSite.title[F]
      thumbnailUri <- videoSite.thumbnailUri[F]
      duration <- videoSite.duration[F]

      downloadUri <- videoSite.downloadUri[F]

      size <-
        Kleisli.liftF {
          client.run(Request[F](Method.HEAD, downloadUri))
            .use(Http4sUtils.header[F](`Content-Length`).map(_.length).run)
        }
    }
    yield models.VideoAnalysisResult(uri, videoSite, videoTitle, duration, size, thumbnailUri)
}
