package com.ruchij.api.web.requests

case class UpdatePlaylistRequest(maybeTitle: Option[String], maybeDescription: Option[String], maybeVideoIdList: Option[Seq[String]])
