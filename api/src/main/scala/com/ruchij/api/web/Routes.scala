package com.ruchij.api.web

import cats.effect.Async
import cats.implicits._
import com.ruchij.api.services.asset.AssetService
import com.ruchij.api.services.authentication.AuthenticationService
import com.ruchij.api.services.health.HealthService
import com.ruchij.api.services.models.Context.RequestContext
import com.ruchij.api.services.playlist.PlaylistService
import com.ruchij.api.services.scheduling.ApiSchedulingService
import com.ruchij.api.services.user.UserService
import com.ruchij.api.services.video.ApiVideoService
import com.ruchij.api.web.middleware.Authenticator.AuthenticatedRequestContextMiddleware
import com.ruchij.api.web.middleware._
import com.ruchij.api.web.routes._
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.messaging.Publisher
import com.ruchij.core.messaging.models.HttpMetric
import com.ruchij.core.services.scheduling.models.DownloadProgress
import com.ruchij.core.services.video.{VideoAnalysisService, VideoWatchHistoryService}
import com.ruchij.core.types.JodaClock
import fs2.Stream
import fs2.compression.Compression
import org.http4s.dsl.Http4sDsl
import org.http4s.server.ContextRouter
import org.http4s.server.middleware.GZip
import org.http4s.{ContextRoutes, HttpApp}

object Routes {

  def apply[F[_]: Async: JodaClock: Compression](
    userService: UserService[F],
    apiVideoService: ApiVideoService[F],
    videoAnalysisService: VideoAnalysisService[F],
    apiSchedulingService: ApiSchedulingService[F],
    playlistService: PlaylistService[F],
    assetService: AssetService[F],
    videoWatchHistoryService: VideoWatchHistoryService[F],
    healthService: HealthService[F],
    authenticationService: AuthenticationService[F],
    downloadProgressStream: Stream[F, DownloadProgress],
    scheduledVideoDownloadUpdatesStream: Stream[F, ScheduledVideoDownload],
    metricPublisher: Publisher[F, HttpMetric],
    allowedOrigins: Set[String]
  ): HttpApp[F] = {
    implicit val dsl: Http4sDsl[F] = new Http4sDsl[F] {}

    val authMiddleware: AuthenticatedRequestContextMiddleware[F] =
      Authenticator.middleware[F](authenticationService, strict = true)

    val contextRoutes: ContextRoutes[RequestContext, F] =
      WebServerRoutes[F] <+>
        ContextRouter[F, RequestContext](
          "/users" -> UserRoutes(userService, authenticationService),
          "/authentication" -> AuthenticationRoutes(authenticationService),
          "/schedule" -> authMiddleware(
            SchedulingRoutes(apiSchedulingService, downloadProgressStream, scheduledVideoDownloadUpdatesStream)
          ),
          "/videos" -> authMiddleware(VideoRoutes(apiVideoService, videoAnalysisService, videoWatchHistoryService)),
          "/playlists" -> authMiddleware(PlaylistRoutes(playlistService)),
          "/assets" -> authMiddleware(AssetRoutes(assetService)),
          "/service" -> ServiceRoutes(healthService),
        )

    MetricsMiddleware(metricPublisher) {
      GZip {
        Cors[F](allowedOrigins) {
          RequestContextMiddleware {
            SentryMiddleware.captureResponseErrors {
              ExceptionHandler {
                SentryMiddleware {
                  NotFoundHandler { contextRoutes }
                }
              }
            }
          }
        }
      }
    }
  }
}
