package com.ruchij.api.web.requests

case class UpdatePlaylistRequest(title: Option[String], description: Option[String], videoIds: Option[Seq[String]])
