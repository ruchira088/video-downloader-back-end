package com.ruchij.core.daos.video.models

import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.videometadata.models.VideoMetadata

case class Video(videoMetadata: VideoMetadata, fileResource: FileResource)
