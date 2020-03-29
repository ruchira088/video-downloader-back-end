package com.ruchij.web.requests

import cats.effect.Sync
import com.ruchij.circe.Decoders.uriDecoder
import org.http4s.{EntityDecoder, Uri}
import org.http4s.circe.jsonOf
import io.circe.generic.auto._

case class SchedulingRequest(url: Uri)

object SchedulingRequest {
  implicit def scheduleRequestDecoder[F[_]: Sync]: EntityDecoder[F, SchedulingRequest] = jsonOf[F, SchedulingRequest]
}
