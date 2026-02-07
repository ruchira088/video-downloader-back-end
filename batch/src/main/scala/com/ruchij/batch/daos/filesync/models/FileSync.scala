package com.ruchij.batch.daos.filesync.models

import java.time.Instant

final case class FileSync(lockedAt: Instant, path: String, syncedAt: Option[Instant])
