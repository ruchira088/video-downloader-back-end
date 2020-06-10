package com.ruchij.daos.snapshot.models

import com.ruchij.daos.resource.models.FileResource

import scala.concurrent.duration.FiniteDuration

case class Snapshot(videoId: String, fileResource: FileResource, videoTimestamp: FiniteDuration)
