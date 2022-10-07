package com.ruchij.core.test.data

import cats.data.NonEmptyList
import cats.effect.kernel.Sync
import cats.implicits._
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.scheduling.models.{ScheduledVideoDownload, SchedulingStatus}
import com.ruchij.core.daos.videometadata.models.CustomVideoSite._
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata}
import com.ruchij.core.types.FunctionKTypes.{FunctionK2TypeOps, eitherLeftFunctor, eitherToF}
import com.ruchij.core.types.{JodaClock, RandomGenerator}
import org.http4s.{MediaType, Uri}
import org.joda.time.DateTime

import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

object DataGenerators {
  def scheduledVideoDownload[F[_]: Sync: JodaClock]: RandomGenerator[F, ScheduledVideoDownload] =
    for {
      videoMetadata <- videoMetadata[F]
      timestamp <- RandomGenerator[F, DateTime]
    }
    yield ScheduledVideoDownload(timestamp, timestamp, SchedulingStatus.Queued, 0, videoMetadata, None)

  def videoMetadata[F[_]: Sync: JodaClock]: RandomGenerator[F, VideoMetadata] =
    for {
      videoSite <- customVideoSite[F]
      id <- RandomGenerator[F, UUID].map(uuid => s"${videoSite.name}-${uuid.toString.take(8)}")
      uri <- RandomGenerator.eval(Uri.fromString(s"https://${videoSite.hostname}/video/$id").toType[F, Throwable])
      title = s"video-tile-$id"
      duration <- RandomGenerator.range(0, 3600).map(seconds => FiniteDuration(seconds, TimeUnit.SECONDS))
      size <- RandomGenerator.range(10_000, 1_000_000).map(_.toLong)
      thumbnail <- fileResource(NonEmptyList.of(MediaType.image.png, MediaType.image.jpeg, MediaType.image.gif))
    }
    yield VideoMetadata(uri, id, videoSite, title, duration, size, thumbnail)

  def fileResource[F[_]: Sync: JodaClock](fileTypes: NonEmptyList[MediaType]): RandomGenerator[F, FileResource] =
    for {
      id <- RandomGenerator[F, UUID].map(uuid => s"file-${uuid.toString.take(8)}")
      timestamp <- RandomGenerator[F, DateTime]
      mediaType <- RandomGenerator.from(fileTypes)
      size <- RandomGenerator.range(1_000, 40_000)
      path = s"/data/files/$id.${mediaType.subType}"
    }
    yield FileResource(id, timestamp, path, mediaType, size)

  def customVideoSite[F[_]: Sync]: RandomGenerator[F, CustomVideoSite] =
    RandomGenerator.from(NonEmptyList.of(SpankBang, PornOne, XFreeHD, TXXX, UPornia, HdZog, HClips))

}
