package com.ruchij.core.utils

import cats.effect.Async
import cats.effect.kernel.{Resource, Sync}
import cats.implicits._
import com.ruchij.core.config.HttpProxyConfiguration
import org.http4s.client.Client
import org.http4s.jdkhttpclient.JdkHttpClient

import java.net.http.HttpClient
import java.net.http.HttpClient.Redirect
import java.net.{InetSocketAddress, ProxySelector}

object Clients {
  private final case class ProxyDetails(hostname: String, port: Int)

  def create[F[_]: Async](
    maybeHttpProxyConfiguration: Option[HttpProxyConfiguration]
  ): Resource[F, Client[F]] = {
    val maybeProxyDetails = for {
      httpProxyConfiguration <- maybeHttpProxyConfiguration
      hostname <- httpProxyConfiguration.proxyUrl.host
      port <- httpProxyConfiguration.proxyUrl.port
    } yield ProxyDetails(hostname.renderString, port)

    Resource
      .make(javaHttpClient(maybeProxyDetails)) { javaHttpClient =>
        Sync[F].delay(javaHttpClient.close())
      }
      .map(javaHttpClient => JdkHttpClient[F](javaHttpClient))
  }

  private def javaHttpClient[F[_]: Sync](maybeProxyDetails: Option[ProxyDetails]): F[HttpClient] = {
    val httpClientBuilder = HttpClient.newBuilder().followRedirects(Redirect.NORMAL)

    maybeProxyDetails match {
      case Some(proxyDetails) =>
        Sync[F]
          .delay(new InetSocketAddress(proxyDetails.hostname, proxyDetails.port))
          .map(ProxySelector.of)
          .flatMap(proxySelector => Sync[F].delay(httpClientBuilder.proxy(proxySelector).build()))

      case None =>
        Sync[F].delay(httpClientBuilder.build())
    }
  }
}
