package com.ruchij.batch.daos.filesync.models

import org.joda.time.DateTime

final case class FileSync(lockedAt: DateTime, path: String, syncedAt: Option[DateTime])
