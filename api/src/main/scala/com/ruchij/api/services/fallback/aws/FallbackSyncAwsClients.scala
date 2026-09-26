package com.ruchij.api.services.fallback.aws

import cats.effect.{Resource, Sync}
import com.ruchij.api.config.FallbackSyncSettings
import software.amazon.awssdk.auth.credentials.{AwsCredentialsProvider, DefaultCredentialsProvider}
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.sqs.SqsAsyncClient

import java.net.URI

final case class FallbackSyncAwsClients(sqs: SqsAsyncClient, dynamoDb: DynamoDbAsyncClient)

object FallbackSyncAwsClients {
  def create[F[_]: Sync](
    settings: FallbackSyncSettings,
    credentialsProvider: AwsCredentialsProvider = DefaultCredentialsProvider.builder().build()
  ): Resource[F, FallbackSyncAwsClients] = {
    val region = Region.of(settings.awsRegion)
    val endpoint = settings.awsEndpointUrl.map(URI.create)

    for {
      // One HTTP client, and so one Netty event loop group, shared by both service clients. The SDK does not close an
      // HTTP client it was handed, so this resource closes it, after the service clients that use it.
      httpClient <- Resource.fromAutoCloseable[F, SdkAsyncHttpClient] {
        Sync[F].delay(NettyNioAsyncHttpClient.builder().build())
      }
      sqs <- Resource.fromAutoCloseable {
        Sync[F].delay {
          val builder =
            SqsAsyncClient.builder().httpClient(httpClient).region(region).credentialsProvider(credentialsProvider)
          endpoint.fold(builder)(builder.endpointOverride).build()
        }
      }
      dynamoDb <- Resource.fromAutoCloseable {
        Sync[F].delay {
          val builder =
            DynamoDbAsyncClient.builder().httpClient(httpClient).region(region).credentialsProvider(credentialsProvider)
          endpoint.fold(builder)(builder.endpointOverride).build()
        }
      }
    } yield FallbackSyncAwsClients(sqs, dynamoDb)
  }
}
