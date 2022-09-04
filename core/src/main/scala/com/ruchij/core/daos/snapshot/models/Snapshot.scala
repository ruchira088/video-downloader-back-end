package com.ruchij.core.daos.snapshot.models

import com.ruchij.core.daos.resource.models.FileResource

import scala.concurrent.duration.FiniteDuration

final case class Snapshot(videoId: String, fileResource: FileResource, videoTimestamp: FiniteDuration)
