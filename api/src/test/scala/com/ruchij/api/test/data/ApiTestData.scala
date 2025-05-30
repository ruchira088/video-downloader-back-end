package com.ruchij.api.test.data

import cats.implicits._
import com.ruchij.api.daos.user.DoobieUserDao
import com.ruchij.api.daos.user.models.{Email, Role, User}
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.video.DoobieVideoDao
import com.ruchij.core.daos.video.models.Video
import com.ruchij.core.daos.videometadata.DoobieVideoMetadataDao
import com.ruchij.core.test.data.CoreTestData
import doobie.free.connection.ConnectionIO

object ApiTestData {

  val AdminUser: User =
    User("john.smith", CoreTestData.Timestamp, "John", "Smith", Email("john.smith@ruchij.com"), Role.Admin)

  val NormalUser: User =
    User("alice.doe", CoreTestData.Timestamp, "Alice", "Doe", Email("alice.doe@gmail.com"), Role.User)

  val setUpData: ConnectionIO[Unit] =
    List(
      DoobieUserDao.insert(AdminUser),
      DoobieUserDao.insert(NormalUser),
      insertVideo(CoreTestData.YouTubeVideo),
      insertVideo(CoreTestData.LocalVideo),
      insertVideo(CoreTestData.SpankBangVideo)
    ).sequence
      .map(_.sum)

  private def insertVideo(video: Video): ConnectionIO[Int] =
    List(
      DoobieFileResourceDao.insert(video.videoMetadata.thumbnail),
      DoobieVideoMetadataDao.insert(video.videoMetadata),
      DoobieFileResourceDao.insert(video.fileResource),
      DoobieVideoDao.insert(video.videoMetadata.id, video.fileResource.id, video.createdAt, video.watchTime)
    ).sequence
      .map(_.sum)
}
