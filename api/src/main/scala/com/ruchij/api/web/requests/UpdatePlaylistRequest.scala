package com.ruchij.api.web.requests

final case class UpdatePlaylistRequest(title: Option[String], description: Option[String], videoIds: Option[Seq[String]])
