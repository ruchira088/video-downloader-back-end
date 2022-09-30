package com.ruchij.api.services.fallback

import cats.effect.Async
import cats.implicits._
import com.ruchij.api.config.FallbackApiConfiguration
import com.ruchij.api.services.fallback.FallbackApiServiceImpl.ResultsList
import com.ruchij.api.services.fallback.models.{ScheduledUrl, UserInformation}
import com.ruchij.core.circe.Decoders._
import fs2.Stream
import io.circe.generic.auto.exportDecoder
import org.http4s.Method.{DELETE, GET}
import org.http4s.circe.CirceEntityDecoder.circeEntityDecoder
import org.http4s.circe.decodeUri
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials}

class FallbackApiServiceImpl[F[_]: Async](client: Client[F], fallbackApiConfiguration: FallbackApiConfiguration)
    extends FallbackApiService[F] {
  private val dsl = new Http4sClientDsl[F] {}

  import dsl._

  private val authorizationToken =
    Authorization(Credentials.Token(AuthScheme.Bearer, fallbackApiConfiguration.bearerToken))

  private val fetchScheduledUrls: F[Seq[ScheduledUrl]] =
    client.expect[ResultsList[ScheduledUrl]] {
      GET(fallbackApiConfiguration.uri / "video-downloader" / "scheduled-urls", authorizationToken)
    }
      .map(_.results)

  override val scheduledUrls: Stream[F, ScheduledUrl] =
    Stream.eval(fetchScheduledUrls)
      .flatMap(urls => if (urls.isEmpty) Stream.empty else Stream.emits(urls) ++ scheduledUrls)

  override def commit(scheduledUrlId: String): F[ScheduledUrl] =
    client.expect[ScheduledUrl] {
      DELETE(
        fallbackApiConfiguration.uri / "video-downloader" / "scheduled-urls" / "id" / scheduledUrlId,
        authorizationToken
      )
    }

  override def userInformation(userId: String): F[UserInformation] =
    client.expect[UserInformation](GET(fallbackApiConfiguration.uri / "user" / "id" / userId, authorizationToken))
}

object FallbackApiServiceImpl {
  final case class ResultsList[A](results: Seq[A])
}
