package com.ruchij.batch.daos.filesync.models

import org.joda.time.DateTime

case class FileSync(lockedAt: DateTime, path: String, syncedAt: Option[DateTime])
