package com.ruchij.core.daos.duplicate.models

import java.time.Instant

case class DuplicateVideo(videoId: String, duplicateGroupId: String, createdAt: Instant)
