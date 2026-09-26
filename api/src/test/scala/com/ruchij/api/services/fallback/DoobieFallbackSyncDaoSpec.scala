package com.ruchij.api.services.fallback

import cats.effect.IO
import cats.implicits._
import com.ruchij.api.services.fallback.FallbackSyncTestData.scheduledVideoDownload
import com.ruchij.core.daos.doobie.DoobieCustomMappings._
import com.ruchij.core.daos.permission.DoobieVideoPermissionDao
import com.ruchij.core.daos.permission.models.VideoPermission
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.scheduling.DoobieSchedulingDao
import com.ruchij.core.daos.scheduling.models.ScheduledVideoDownload
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.Clock
import doobie.ConnectionIO
import doobie.implicits._
import org.http4s.Uri
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global

class DoobieFallbackSyncDaoSpec extends AnyFlatSpec with Matchers {

  private def insertTestUser(userId: String, timestamp: Instant): ConnectionIO[Int] =
    sql"""
      INSERT INTO api_user (id, created_at, first_name, last_name, email, role)
        VALUES ($userId, $timestamp, 'Test', 'User', ${s"$userId@test.com"}, 'User')
    """.update.run

  private def insertVideo(video: ScheduledVideoDownload): ConnectionIO[Unit] =
    DoobieFileResourceDao.insert(video.videoMetadata.thumbnail) *>
      DoobieVideoMetadataDao.insert(video.videoMetadata) *>
      DoobieSchedulingDao.insert(video).void

  "DoobieFallbackSyncDao" should "read videos with their permission user ids" in runIO {
    new EmbeddedCoreResourcesProvider[IO].transactor.use { transaction =>
      val dao = new DoobieFallbackSyncDao(DoobieSchedulingDao, DoobieVideoPermissionDao, pageSize = 2)

      // `scheduledVideoDownload` fixes the URL, thumbnail path (both unique columns) and scheduledAt; give each of
      // the five videos its own, so they can all be inserted and paging by date has a stable order.
      val videos = (1 to 5).toList.map { index =>
        val video = scheduledVideoDownload(s"video-$index")
        video.copy(
          scheduledAt = video.scheduledAt.plusSeconds(index.toLong),
          videoMetadata = video.videoMetadata.copy(
            url = Uri.unsafeFromString(s"https://example.com/video-$index"),
            thumbnail = video.videoMetadata.thumbnail.copy(path = s"/opt/thumbnail-$index.jpg")
          )
        )
      }

      for {
        timestamp <- Clock[IO].timestamp
        _ <- transaction {
          insertTestUser("user-1", timestamp) *> insertTestUser("user-2", timestamp) *>
            videos.traverse_(insertVideo) *>
            DoobieVideoPermissionDao.insert(VideoPermission(timestamp, "video-1", "user-1")) *>
            DoobieVideoPermissionDao.insert(VideoPermission(timestamp, "video-1", "user-2")).void
        }
        one <- transaction(dao.findById("video-1"))
        missing <- transaction(dao.findById("nope"))
        all <- transaction(dao.findAll)
      } yield {
        one.map(_.userIds.sorted) mustBe Some(List("user-1", "user-2"))
        missing mustBe None
        all.map(_.scheduledVideoDownload.videoMetadata.id).sorted mustBe videos.map(_.videoMetadata.id).sorted
        all.find(_.scheduledVideoDownload.videoMetadata.id == "video-2").map(_.userIds) mustBe Some(Nil)
      }
    }
  }
}
