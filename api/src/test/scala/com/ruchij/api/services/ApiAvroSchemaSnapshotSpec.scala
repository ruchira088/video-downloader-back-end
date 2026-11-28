package com.ruchij.api.services

import com.ruchij.api.services.fallback.models.FallbackSyncRequest.FallbackSyncRequestTopic
import com.ruchij.api.services.health.models.messaging.HealthCheckMessage.HealthCheckTopic
import com.ruchij.api.services.scheduling.models.ScheduledVideoDeletion.ScheduledVideoDeletionTopic
import com.ruchij.core.messaging.AvroSchemaSnapshotSpec

// Add every new MessagingTopic defined in api here, so that its schema is snapshotted too
class ApiAvroSchemaSnapshotSpec
    extends AvroSchemaSnapshotSpec("api", List(ScheduledVideoDeletionTopic, HealthCheckTopic, FallbackSyncRequestTopic))
