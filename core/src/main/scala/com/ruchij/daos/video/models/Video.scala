package com.ruchij.daos.video.models

import com.ruchij.daos.resource.models.FileResource
import com.ruchij.daos.videometadata.models.VideoMetadata

case class Video(videoMetadata: VideoMetadata, fileResource: FileResource)
