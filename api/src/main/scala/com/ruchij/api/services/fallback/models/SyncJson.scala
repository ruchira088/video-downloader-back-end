package com.ruchij.api.services.fallback.models

import io.circe.parser.decode
import io.circe.{Decoder, DecodingFailure, Encoder, Json}

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import scala.util.Try

object SyncJson {
  private val TimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

  def formatTimestamp(instant: Instant): String = TimestampFormatter.format(instant)

  private def timestamp(instant: Instant): Json = Json.fromString(formatTimestamp(instant))

  private val timestampDecoder: Decoder[Instant] =
    Decoder.decodeString.emapTry(value => Try(Instant.parse(value)))

  private def upsertJson(upsert: ScheduledVideoUpsert): Json =
    Json
      .obj(
        "type" -> Json.fromString("ScheduledVideoUpsert"),
        "videoId" -> Json.fromString(upsert.videoId),
        "capturedAt" -> timestamp(upsert.capturedAt),
        "hash" -> Json.fromString(upsert.hash),
        "userIds" -> Json.arr(upsert.userIds.map(Json.fromString): _*),
        "url" -> Json.fromString(upsert.url),
        "videoSite" -> Json.fromString(upsert.videoSite),
        "title" -> Json.fromString(upsert.title),
        "durationMs" -> Json.fromLong(upsert.durationMs),
        "sizeBytes" -> Json.fromLong(upsert.sizeBytes),
        "status" -> Json.fromString(upsert.status),
        "scheduledAt" -> timestamp(upsert.scheduledAt),
        "completedAt" -> upsert.completedAt.fold(Json.Null)(timestamp)
      )
      .dropNullValues

  implicit val mainToFallbackMessageEncoder: Encoder[MainToFallbackMessage] =
    Encoder.instance {
      case upsert: ScheduledVideoUpsert => upsertJson(upsert)

      case ScheduledVideoRemoval(videoId, capturedAt) =>
        Json.obj(
          "type" -> Json.fromString("ScheduledVideoRemoval"),
          "videoId" -> Json.fromString(videoId),
          "capturedAt" -> timestamp(capturedAt)
        )

      case RequestResolved(requestId, userId, outcome) =>
        val outcomeJson =
          outcome match {
            case ResolutionOutcome.Scheduled(upsert) =>
              Json.obj("result" -> Json.fromString("Scheduled"), "upsert" -> upsertJson(upsert))

            case ResolutionOutcome.Rejected(reason) =>
              Json.obj("result" -> Json.fromString("Rejected"), "reason" -> Json.fromString(reason))
          }

        Json.obj(
          "type" -> Json.fromString("RequestResolved"),
          "requestId" -> Json.fromString(requestId),
          "userId" -> Json.fromString(userId),
          "outcome" -> outcomeJson
        )
    }

  implicit val scheduleRequestEncoder: Encoder[ScheduleRequest] =
    Encoder.instance { request =>
      Json.obj(
        "type" -> Json.fromString("ScheduleRequest"),
        "requestId" -> Json.fromString(request.requestId),
        "userId" -> Json.fromString(request.userId),
        "url" -> Json.fromString(request.url),
        "requestedAt" -> timestamp(request.requestedAt)
      )
    }

  implicit val scheduleRequestDecoder: Decoder[ScheduleRequest] =
    Decoder.instance { cursor =>
      for {
        messageType <- cursor.get[String]("type")
        _ <- Either.cond(
          messageType == "ScheduleRequest",
          (),
          DecodingFailure(s"Unexpected message type: $messageType", cursor.history)
        )
        requestId <- cursor.get[String]("requestId")
        userId <- cursor.get[String]("userId")
        url <- cursor.get[String]("url")
        requestedAt <- cursor.get[Instant]("requestedAt")(timestampDecoder)
      } yield ScheduleRequest(requestId, userId, url, requestedAt)
    }

  def encode(message: MainToFallbackMessage): String = mainToFallbackMessageEncoder(message).noSpaces

  def decodeScheduleRequest(body: String): Either[io.circe.Error, ScheduleRequest] =
    decode[ScheduleRequest](body)(scheduleRequestDecoder)
}
