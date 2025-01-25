package com.ruchij.core.daos.video

import cats.data.NonEmptyList
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.doobie.DoobieUtils.{SingleUpdateOps, ordering, sortByFieldName}
import com.ruchij.core.daos.scheduling.models.RangeValue
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.models.VideoSite
import com.ruchij.core.services.models.{Order, SortBy}
import doobie.Fragments.{in, whereAndOpt}
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import doobie.util.fragment.Fragment
import org.http4s.Uri

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

object DoobieVideoDao extends VideoDao[ConnectionIO] {

  private def selectQuery(hasUserId: Boolean): Fragment =
    fr"""
       SELECT
        video_metadata.url,
        video_metadata.id,
        video_metadata.video_site,
    """ ++ (if (hasUserId) fr"video_title.title," else fr"video_metadata.title,") ++
      fr"""
        video_metadata.duration,
        video_metadata.size,
        thumbnail.id, thumbnail.created_at, thumbnail.path, thumbnail.media_type, thumbnail.size,
        video_file.id,
        video_file.created_at,
        video_file.path,
        video_file.media_type,
        video_file.size,
        video_watch_time.watch_time_in_ms
      FROM video
      INNER JOIN video_metadata ON video.video_metadata_id = video_metadata.id
      INNER JOIN file_resource AS thumbnail ON video_metadata.thumbnail_id = thumbnail.id
      INNER JOIN file_resource AS video_file ON video.file_resource_id = video_file.id
      INNER JOIN video_watch_time ON video.video_metadata_id = video_watch_time.video_id
    """ ++ (
      if (hasUserId)
        fr"""
          INNER JOIN permission ON permission.video_id = video.video_metadata_id
          LEFT JOIN video_title ON video_title.video_id = video.video_metadata_id
        """
      else Fragment.empty
    )

  override def insert(
    videoMetadataId: String,
    videoFileResourceId: String,
    finiteDuration: FiniteDuration
  ): ConnectionIO[Int] =
    sql"""
        INSERT INTO video (video_metadata_id, file_resource_id)
            VALUES ($videoMetadataId, $videoFileResourceId)
    """.update.run.flatMap {
      result =>
        sql"""
         INSERT INTO video_watch_time (video_id, watch_time_in_ms)
            VALUES ($videoMetadataId, $finiteDuration)
           """
          .update
          .run.map(_ + result)
    }

  override def search(
    term: Option[String],
    videoUrls: Option[NonEmptyList[Uri]],
    durationRange: RangeValue[FiniteDuration],
    sizeRange: RangeValue[Long],
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order,
    videoSites: Option[NonEmptyList[VideoSite]],
    maybeUserId: Option[String]
  ): ConnectionIO[Seq[Video]] =
    (selectQuery(maybeUserId.nonEmpty)
      ++
        whereAndOpt(
          term.map(searchTerm => fr"video_metadata.title ILIKE ${"%" + searchTerm + "%"}"),
          videoUrls.map(urls => in(fr"video_metadata.url", urls)),
          durationRange.min.map(minimum => fr"video_metadata.duration >= $minimum"),
          durationRange.max.map(maximum => fr"video_metadata.duration <= $maximum"),
          sizeRange.min.map(minimum => fr"video_metadata.size >= $minimum"),
          sizeRange.max.map(maximum => fr"video_metadata.size <= $maximum"),
          videoSites.map(sites => in(fr"video_metadata.video_site", sites)),
          maybeUserId.map(userId => fr"permission.user_id = $userId"),
          maybeUserId.map(userId => fr"video_title.user_id = $userId")
        )
      ++ fr"ORDER BY"
      ++ videoSortByFieldName(sortBy)
      ++ ordering(order)
      ++ fr"LIMIT $pageSize OFFSET ${pageSize * pageNumber}")
      .query[Video]
      .to[Seq]

  override def findById(id: String, maybeUserId: Option[String]): ConnectionIO[Option[Video]] =
    (selectQuery(maybeUserId.nonEmpty) ++
      whereAndOpt(
        Some(fr"video_metadata_id = $id"),
        maybeUserId.map(userId => fr"permission.user_id = $userId"),
        maybeUserId.map(userId => fr"video_title.user_id = $userId")
      ))
      .query[Video]
      .option

  private val videoSortByFieldName: SortBy => Fragment =
    sortByFieldName.orElse {
      case SortBy.Date => fr"video.created_at, video.video_metadata_id"
      case SortBy.WatchTime => fr"video_watch_time.watch_time_in_ms, video.video_metadata_id"
      case _ => fr"RANDOM()"
    }

  override def findByVideoFileResourceId(fileResourceId: String): ConnectionIO[Option[Video]] =
    (selectQuery(false) ++ fr"WHERE video_file.id = $fileResourceId").query[Video].option

  override def findByVideoPath(videoPath: String): ConnectionIO[Option[Video]] =
    (selectQuery(false) ++ fr"WHERE video_file.path = $videoPath").query[Video].option

  override def incrementWatchTime(
    videoId: String,
    finiteDuration: FiniteDuration
  ): ConnectionIO[Option[FiniteDuration]] =
    sql"UPDATE video_watch_time SET watch_time_in_ms = watch_time_in_ms + $finiteDuration WHERE video_id = $videoId".update.run.singleUpdate.semiflatMap {
      _ =>
        sql"SELECT watch_time_in_ms FROM video_watch_time WHERE video_id = $videoId".query[FiniteDuration].unique
    }.value

  override def deleteById(videoId: String): ConnectionIO[Int] =
    sql"DELETE FROM video_watch_time WHERE video_id = $videoId".update.run.flatMap {
      count => sql"DELETE FROM video WHERE video_metadata_id = $videoId".update.run.map(_ + count)
    }

  override def hasVideoFilePermission(videoFileResourceId: String, userId: String): ConnectionIO[Boolean] =
    sql"""
      SELECT COUNT(*) FROM video
        JOIN permission ON video.video_metadata_id = permission.video_id
        WHERE video.file_resource_id = $videoFileResourceId AND permission.user_id = $userId
    """
      .query[Int]
      .unique
      .map(_ == 1)

  override val count: ConnectionIO[Int] =
    sql"SELECT COUNT(*) FROM video".query[Int].unique

  override val duration: ConnectionIO[FiniteDuration] =
    sql"""
      SELECT SUM(video_metadata.duration)
      FROM video
      JOIN video_metadata ON video.video_metadata_id = video_metadata.id
    """
      .query[Option[FiniteDuration]]
      .unique
      .map(_.getOrElse(FiniteDuration(0, TimeUnit.MILLISECONDS)))

  override val size: ConnectionIO[Long] =
    sql"""
      SELECT SUM(file_resource.size)
      FROM video
      JOIN file_resource ON video.file_resource_id = file_resource.id
    """
      .query[Option[Long]]
      .unique
      .map(_.getOrElse(0))

  override val sites: ConnectionIO[Set[VideoSite]] =
    sql"""
      SELECT DISTINCT video_metadata.video_site
      FROM video
      JOIN video_metadata ON video.video_metadata_id = video_metadata.id
    """
      .query[VideoSite]
      .to[Set]
}
