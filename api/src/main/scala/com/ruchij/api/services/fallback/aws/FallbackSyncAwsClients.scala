package com.ruchij.api.services.fallback.aws

import cats.effect.{Resource, Sync}
import com.ruchij.api.config.FallbackSyncSettings
import software.amazon.awssdk.auth.credentials.{AwsCredentialsProvider, DefaultCredentialsProvider}
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
      sqs <- Resource.fromAutoCloseable {
        Sync[F].delay {
          val builder = SqsAsyncClient.builder().region(region).credentialsProvider(credentialsProvider)
          endpoint.fold(builder)(builder.endpointOverride).build()
        }
      }
      dynamoDb <- Resource.fromAutoCloseable {
        Sync[F].delay {
          val builder = DynamoDbAsyncClient.builder().region(region).credentialsProvider(credentialsProvider)
          endpoint.fold(builder)(builder.endpointOverride).build()
        }
      }
    } yield FallbackSyncAwsClients(sqs, dynamoDb)
  }
}
