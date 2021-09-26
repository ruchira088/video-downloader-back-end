package com.ruchij.api.web

import cats.effect.{Blocker, Concurrent, ContextShift, Timer}
import cats.implicits._
import com.ruchij.api.services.authentication.AuthenticationService
import com.ruchij.api.services.health.HealthService
import com.ruchij.api.services.models.Context.RequestContext
import com.ruchij.api.services.playlist.PlaylistService
import com.ruchij.api.services.scheduling.ApiSchedulingService
import com.ruchij.api.services.user.UserService
import com.ruchij.api.web.middleware.Authenticator.AuthenticatedRequestContextMiddleware
import com.ruchij.api.web.middleware._
import com.ruchij.api.web.routes._
import com.ruchij.core.messaging.Publisher
import com.ruchij.core.messaging.models.HttpMetric
import com.ruchij.core.services.asset.AssetService
import com.ruchij.core.services.scheduling.models.DownloadProgress
import com.ruchij.core.services.video.{VideoAnalysisService, VideoService}
import fs2.Stream
import org.http4s.dsl.Http4sDsl
import org.http4s.server.ContextRouter
import org.http4s.server.middleware.{CORS, GZip}
import org.http4s.{ContextRoutes, HttpApp}

object Routes {

  def apply[F[+ _]: Concurrent: Timer: ContextShift](
    userService: UserService[F],
    videoService: VideoService[F],
    videoAnalysisService: VideoAnalysisService[F],
    apiSchedulingService: ApiSchedulingService[F],
    playlistService: PlaylistService[F],
    assetService: AssetService[F],
    healthService: HealthService[F],
    authenticationService: AuthenticationService[F],
    downloadProgressStream: Stream[F, DownloadProgress],
    metricPublisher: Publisher[F, HttpMetric],
    blockerIO: Blocker
  ): HttpApp[F] = {
    implicit val dsl: Http4sDsl[F] = new Http4sDsl[F] {}

    val authMiddleware: AuthenticatedRequestContextMiddleware[F] =
      Authenticator.middleware[F](authenticationService, strict = true)

    val contextRoutes: ContextRoutes[RequestContext, F] =
      WebServerRoutes(blockerIO) <+>
        ContextRouter[F, RequestContext](
          "/users" -> UserRoutes(userService),
          "/authentication" -> AuthenticationRoutes(authenticationService),
          "/schedule" -> authMiddleware(SchedulingRoutes(apiSchedulingService, downloadProgressStream)),
          "/videos" -> authMiddleware(VideoRoutes(videoService, videoAnalysisService)),
          "/playlists" -> authMiddleware(PlaylistRoutes(playlistService)),
          "/assets" -> authMiddleware(AssetRoutes(assetService)),
          "/service" -> ServiceRoutes(healthService),
        )

    val cors =
      CORS.policy
        .withAllowCredentials(true)
        .withAllowOriginHost { _ =>
          true
        }

    MetricsMiddleware(metricPublisher) {
      GZip {
        cors {
          ExceptionHandler {
            NotFoundHandler {
              RequestContextMiddleware { contextRoutes }
            }
          }
        }
      }
    }
  }
}
