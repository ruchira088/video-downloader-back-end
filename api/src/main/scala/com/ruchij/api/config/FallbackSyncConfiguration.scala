package com.ruchij.api.config

final case class FallbackSyncSettings(
  mainToFallbackQueueUrl: String,
  fallbackToMainQueueUrl: String,
  tableName: String,
  awsRegion: String,
  awsEndpointUrl: Option[String],
  reconcileAllowMassRemoval: Boolean = false
)

final case class FallbackSyncConfiguration(
  enabled: Boolean,
  mainToFallbackQueueUrl: Option[String],
  fallbackToMainQueueUrl: Option[String],
  tableName: Option[String],
  awsRegion: Option[String],
  awsEndpointUrl: Option[String],
  reconcileAllowMassRemoval: Boolean = false
) {
  val settings: Either[IllegalArgumentException, Option[FallbackSyncSettings]] =
    if (!enabled) Right(None)
    else
      (mainToFallbackQueueUrl, fallbackToMainQueueUrl, tableName, awsRegion) match {
        case (Some(mainToFallback), Some(fallbackToMain), Some(table), Some(region)) =>
          Right {
            Some {
              FallbackSyncSettings(
                mainToFallback,
                fallbackToMain,
                table,
                region,
                awsEndpointUrl,
                reconcileAllowMassRemoval
              )
            }
          }

        case _ =>
          Left {
            new IllegalArgumentException(
              "FALLBACK_SYNC_ENABLED is true, so FALLBACK_SYNC_MAIN_TO_FALLBACK_QUEUE_URL, " +
                "FALLBACK_SYNC_FALLBACK_TO_MAIN_QUEUE_URL, FALLBACK_SYNC_TABLE_NAME and FALLBACK_SYNC_AWS_REGION " +
                "must all be set"
            )
          }
      }
}

object FallbackSyncConfiguration {
  val Disabled: FallbackSyncConfiguration = FallbackSyncConfiguration(false, None, None, None, None, None)
}
