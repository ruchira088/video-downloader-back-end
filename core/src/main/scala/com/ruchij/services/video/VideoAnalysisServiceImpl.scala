package com.ruchij.services.video

import cats.effect.Sync
import cats.implicits._
import com.ruchij.daos.videometadata.models
import com.ruchij.daos.videometadata.models.{VideoMetadata, VideoSite}
import com.ruchij.services.hashing.HashingService
import com.ruchij.utils.Http4sUtils
import org.http4s.client.Client
import org.http4s.{Method, Request, Uri}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class VideoAnalysisServiceImpl[F[_]: Sync](client: Client[F], hashingService: HashingService[F])
    extends VideoAnalysisService[F] {

  override def metadata(uri: Uri): F[VideoMetadata] =
    for {
      key <- hashingService.hash(uri.renderString)
      (videoSite, document) <- uriInfo(uri)

      videoTitle <- videoSite.title[F].apply(document)
      thumbnailUri <- videoSite.thumbnailUri[F].apply(document)
      duration <- videoSite.duration[F].apply(document)

      downloadUri <- videoSite.downloadUri[F].apply(document)
      size <- client.run(Request[F](Method.HEAD, downloadUri)).use(Http4sUtils.contentLength[F])

    } yield models.VideoMetadata(uri, key, videoSite, videoTitle, duration, size, thumbnailUri)

  override def downloadUri(uri: Uri): F[Uri] =
    for {
      (videoSite, document) <- uriInfo(uri)
      downloadUri <- videoSite.downloadUri[F].apply(document)
    } yield downloadUri

  def uriInfo(uri: Uri): F[(VideoSite, Document)] =
    for {
      videoSite <- VideoSite.infer[F](uri)

      html <- client.expect[String](uri)
      document <- Sync[F].delay(Jsoup.parse(html))
    } yield (videoSite, document)
}
