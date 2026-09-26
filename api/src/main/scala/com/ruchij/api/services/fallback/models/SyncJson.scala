package com.ruchij.api.services.fallback.models

import io.circe.parser.decode
import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json}

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{Instant, ZoneOffset}
import scala.util.Try

object SyncJson {
  // Fixed width, so the fallback can compare capturedAt values as strings. Anything finer than microseconds is
  // truncated: both databases (Postgres and DynamoDB, via the Python side) keep at most microseconds.
  private val TimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)

  private val TimestampPattern = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z".r

  def formatTimestamp(instant: Instant): String = TimestampFormatter.format(instant.truncatedTo(ChronoUnit.MICROS))

  def parseTimestamp(value: String): Either[String, Instant] =
    if (TimestampPattern.matches(value))
      Try(Instant.parse(value)).toEither.left.map(error => s"Invalid timestamp $value: ${error.getMessage}")
    else Left(s"Timestamp $value is not in the yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z' format")

  private def timestamp(instant: Instant): Json = Json.fromString(formatTimestamp(instant))

  private val timestampDecoder: Decoder[Instant] = Decoder.decodeString.emap(parseTimestamp)

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
        _ <- messageType(cursor, "ScheduleRequest")
        requestId <- cursor.get[String]("requestId")
        userId <- cursor.get[String]("userId")
        url <- cursor.get[String]("url")
        requestedAt <- cursor.get[Instant]("requestedAt")(timestampDecoder)
      } yield ScheduleRequest(requestId, userId, url, requestedAt)
    }

  private def messageType(cursor: HCursor, expected: String): Decoder.Result[Unit] =
    cursor.get[String]("type").flatMap { actual =>
      Either.cond(actual == expected, (), DecodingFailure(s"Unexpected message type: $actual", cursor.history))
    }

  private val upsertDecoder: Decoder[ScheduledVideoUpsert] =
    Decoder.instance { cursor =>
      for {
        _ <- messageType(cursor, "ScheduledVideoUpsert")
        videoId <- cursor.get[String]("videoId")
        capturedAt <- cursor.get[Instant]("capturedAt")(timestampDecoder)
        hash <- cursor.get[String]("hash")
        userIds <- cursor.get[List[String]]("userIds")
        url <- cursor.get[String]("url")
        videoSite <- cursor.get[String]("videoSite")
        title <- cursor.get[String]("title")
        durationMs <- cursor.get[Long]("durationMs")
        sizeBytes <- cursor.get[Long]("sizeBytes")
        status <- cursor.get[String]("status")
        scheduledAt <- cursor.get[Instant]("scheduledAt")(timestampDecoder)
        completedAt <- cursor.get[Option[Instant]]("completedAt")(Decoder.decodeOption(timestampDecoder))
      } yield
        ScheduledVideoUpsert(
          videoId,
          capturedAt,
          hash,
          userIds,
          url,
          videoSite,
          title,
          durationMs,
          sizeBytes,
          status,
          scheduledAt,
          completedAt
        )
    }

  /** The main side only decodes the replies it stored itself (see FallbackRequestConsumer). */
  implicit val requestResolvedDecoder: Decoder[RequestResolved] =
    Decoder.instance { cursor =>
      val outcome = cursor.downField("outcome")

      for {
        _ <- messageType(cursor, "RequestResolved")
        requestId <- cursor.get[String]("requestId")
        userId <- cursor.get[String]("userId")
        result <- outcome.get[String]("result")
        resolution <- result match {
          case "Scheduled" =>
            outcome.get[ScheduledVideoUpsert]("upsert")(upsertDecoder).map(ResolutionOutcome.Scheduled)
          case "Rejected" => outcome.get[String]("reason").map(ResolutionOutcome.Rejected)
          case other => Left(DecodingFailure(s"Unexpected outcome: $other", outcome.history))
        }
      } yield RequestResolved(requestId, userId, resolution)
    }

  def encode(message: MainToFallbackMessage): String = mainToFallbackMessageEncoder(message).noSpaces

  def decodeRequestResolved(body: String): Either[io.circe.Error, RequestResolved] =
    decode[RequestResolved](body)(requestResolvedDecoder)

  def decodeScheduleRequest(body: String): Either[io.circe.Error, ScheduleRequest] =
    decode[ScheduleRequest](body)(scheduleRequestDecoder)
}
