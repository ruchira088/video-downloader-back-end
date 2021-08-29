package com.ruchij.core.daos.video

import cats.data.{NonEmptyList, OptionT}
import cats.implicits._
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

import scala.concurrent.duration.FiniteDuration

object DoobieVideoDao extends VideoDao[ConnectionIO] {

  val SelectQuery =
    fr"""
       SELECT
        video_metadata.url,
        video_metadata.id,
        video_metadata.video_site,
        video_metadata.title,
        video_metadata.duration,
        video_metadata.size,
        thumbnail.id, thumbnail.created_at, thumbnail.path, thumbnail.media_type, thumbnail.size,
        video_file.id,
        video_file.created_at,
        video_file.path,
        video_file.media_type,
        video_file.size,
        video.watch_time
      FROM video
      JOIN video_metadata ON video.video_metadata_id = video_metadata.id
      JOIN file_resource AS thumbnail ON video_metadata.thumbnail_id = thumbnail.id
      JOIN file_resource AS video_file ON video.file_resource_id = video_file.id
    """

  override def insert(videoMetadataId: String, videoFileResourceId: String, finiteDuration: FiniteDuration): ConnectionIO[Int] =
    sql"""
        INSERT INTO video (video_metadata_id, file_resource_id, watch_time)
            VALUES ($videoMetadataId, $videoFileResourceId, ${finiteDuration.toMillis})
    """
      .update.run

  override def search(
    term: Option[String],
    durationRange: RangeValue[FiniteDuration],
    sizeRange: RangeValue[Long],
    pageNumber: Int,
    pageSize: Int,
    sortBy: SortBy,
    order: Order,
    videoSites: Option[NonEmptyList[VideoSite]]
  ): ConnectionIO[Seq[Video]] =
    (SelectQuery
      ++
        whereAndOpt(
          term.map(searchTerm => fr"video_metadata.title ILIKE ${"%" + searchTerm + "%"}"),
          durationRange.min.map(minimum => fr"video_metadata.duration >= $minimum"),
          durationRange.max.map(maximum => fr"video_metadata.duration <= $maximum"),
          sizeRange.min.map(minimum => fr"video_metadata.size >= $minimum"),
          sizeRange.max.map(maximum => fr"video_metadata.size <= $maximum"),
          videoSites.map(sites => in(fr"video_metadata.video_site", sites))
        )
      ++ fr"ORDER BY"
      ++ videoSortByFieldName(sortBy)
      ++ ordering(order)
      ++ fr"LIMIT $pageSize OFFSET ${pageSize * pageNumber}")
      .query[Video]
      .to[Seq]

  override def findById(id: String): ConnectionIO[Option[Video]] =
    (SelectQuery ++ fr"WHERE video_metadata_id = $id").query[Video].option

  val videoSortByFieldName: SortBy => Fragment =
    sortByFieldName.orElse {
      case SortBy.Date => fr"video.created_at"
      case SortBy.WatchTime => fr"video.watch_time"
      case _ => fr"RANDOM()"
    }

  override def findByVideoFileResourceId(fileResourceId: String): ConnectionIO[Option[Video]] =
    (SelectQuery ++ fr"WHERE video_file.id = $fileResourceId").query[Video].option

  override def incrementWatchTime(videoId: String, finiteDuration: FiniteDuration): ConnectionIO[Option[FiniteDuration]] =
    sql"UPDATE video SET watch_time = watch_time + $finiteDuration WHERE video_metadata_id = $videoId"
      .update
      .run
      .singleUpdate
      .semiflatMap {
        _ => sql"SELECT watch_time FROM video WHERE video_metadata_id = $videoId".query[FiniteDuration].unique
      }
      .value

  override def deleteById(videoId: String): ConnectionIO[Option[Video]] =
    OptionT(findById(videoId))
      .flatMap { video =>
        sql"DELETE FROM video WHERE video_metadata_id = $videoId"
          .update
          .run
          .singleUpdate
          .as(video)
      }
      .value

  override val count: ConnectionIO[Int] =
    sql"SELECT COUNT(*) FROM video".query[Int].unique

  override val duration: ConnectionIO[FiniteDuration] =
    sql"""
      SELECT SUM(video_metadata.duration)
      FROM video
      JOIN video_metadata ON video.video_metadata_id = video_metadata.id
    """
      .query[FiniteDuration]
      .unique

  override val size: ConnectionIO[Long] =
    sql"""
      SELECT SUM(file_resource.size)
      FROM video
      JOIN file_resource ON video.file_resource_id = file_resource.id
    """
      .query[Long]
      .unique
}
