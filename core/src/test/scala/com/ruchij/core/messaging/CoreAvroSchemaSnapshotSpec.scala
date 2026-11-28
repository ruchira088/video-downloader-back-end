package com.ruchij.core.messaging

import com.ruchij.core.messaging.MessagingTopic._

// Add every new MessagingTopic defined in core here, so that its schema is snapshotted too
class CoreAvroSchemaSnapshotSpec
    extends AvroSchemaSnapshotSpec(
      "core",
      List(
        ScheduledVideoDownloadTopic,
        DownloadProgressTopic,
        HttpMetricTopic,
        WorkerStatusUpdateTopic,
        ScanVideoCommandTopic,
        VideoWatchMetricTopic
      )
    )
