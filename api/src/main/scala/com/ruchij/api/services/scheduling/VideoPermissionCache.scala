package com.ruchij.api.services.scheduling

import cats.effect.implicits._
import cats.effect.{Concurrent, Deferred, Poll, Ref}
import cats.implicits._
import com.ruchij.core.types.Clock

import java.time.Instant
import scala.concurrent.duration._
import scala.language.postfixOps

/** Answers whether a single user has permission for (i.e. owns) a video, caching the answers. */
trait VideoPermissionCache[F[_]] {
  def hasPermission(videoId: String): F[Boolean]
}

object VideoPermissionCache {

  /**
    * @param maxEntries   upper bound on the number of cached videos; the least recently looked up entry is evicted
    * @param permittedTtl how long a "has permission" answer is trusted, so a removed permission is eventually noticed
    * @param deniedTtl    how long a "no permission" answer is trusted, so a newly scheduled video is picked up quickly
    */
  final case class Settings(maxEntries: Int, permittedTtl: FiniteDuration, deniedTtl: FiniteDuration)

  val DefaultSettings: Settings = Settings(maxEntries = 1000, permittedTtl = 5 minutes, deniedTtl = 10 seconds)

  private sealed trait Entry[F[_]] {
    val checkedAt: Instant
  }

  /** A look up in progress, shared by every caller that asks about the same video meanwhile */
  private final case class Pending[F[_]](result: Deferred[F, Either[Throwable, Boolean]], checkedAt: Instant)
      extends Entry[F]

  private final case class Resolved[F[_]](hasPermission: Boolean, checkedAt: Instant) extends Entry[F]

  def create[F[_]: Concurrent: Clock](
    lookup: String => F[Boolean],
    settings: Settings = DefaultSettings
  ): F[VideoPermissionCache[F]] =
    Ref.of[F, Map[String, Entry[F]]](Map.empty).map { cacheRef =>
      new VideoPermissionCache[F] {
        private def isFresh(entry: Entry[F], now: Instant): Boolean =
          entry match {
            case Pending(_, _) => true
            case Resolved(hasPermission, checkedAt) =>
              val ttl = if (hasPermission) settings.permittedTtl else settings.deniedTtl
              now.isBefore(checkedAt.plusMillis(ttl.toMillis))
          }

        private def store(videoId: String, entry: Entry[F], cache: Map[String, Entry[F]]): Map[String, Entry[F]] = {
          val withoutStale = (cache - videoId).filter { case (_, cached) => isFresh(cached, entry.checkedAt) }

          val bounded =
            if (withoutStale.size < settings.maxEntries) withoutStale
            else
              withoutStale.removedAll(
                withoutStale.toList
                  .sortBy { case (_, cached) => cached.checkedAt }
                  .take(withoutStale.size - settings.maxEntries + 1)
                  .map { case (cachedVideoId, _) => cachedVideoId }
              )

          bounded + (videoId -> entry)
        }

        private def runLookup(videoId: String, pending: Pending[F], poll: Poll[F]): F[Boolean] =
          poll(Concurrent[F].unit.flatMap(_ => lookup(videoId)))
            .onCancel {
              cacheRef
                .update(_ - videoId)
                .productR {
                  pending.result.complete(Left(new IllegalStateException(s"Permission look up for $videoId cancelled")))
                }
                .void
            }
            .attempt
            .flatTap { result =>
              cacheRef
                .update { cache =>
                  result.fold(
                    _ => cache - videoId,
                    hasPermission => store(videoId, Resolved[F](hasPermission, pending.checkedAt), cache)
                  )
                }
                .productR(pending.result.complete(result))
            }
            .rethrow

        override def hasPermission(videoId: String): F[Boolean] =
          Concurrent[F].uncancelable { poll =>
            for {
              now <- Clock[F].timestamp
              deferred <- Deferred[F, Either[Throwable, Boolean]]

              // modify may re-run this function on contention, so the actions it returns must be lazy
              action <- cacheRef.modify[F[Boolean]] { cache =>
                cache.get(videoId).filter(entry => isFresh(entry, now)) match {
                  case Some(Resolved(hasPermission, _)) => cache -> Concurrent[F].pure(hasPermission)

                  case Some(Pending(result, _)) => cache -> poll(result.get).rethrow

                  case None =>
                    val pending = Pending[F](deferred, now)
                    store(videoId, pending, cache) -> runLookup(videoId, pending, poll)
                }
              }

              result <- action
            } yield result
          }
      }
    }
}
