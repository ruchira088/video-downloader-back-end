package com.ruchij.core.daos.resource

import cats.effect.IO
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.test.Providers._
import com.ruchij.core.test.{DoobieProvider, IOSupport}
import com.ruchij.core.types.JodaClock
import org.http4s.MediaType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global

class DoobieFileResourceDaoSpec extends AnyFlatSpec with Matchers with IOSupport {

  "DoobieFileResource" should "perform CRUD operation" in run {
    DoobieProvider.h2InMemoryTransactor[IO]
      .use {
        transaction =>
          for {
            timestamp <- JodaClock[IO].timestamp
            fileResource = FileResource("file-id", timestamp, "/video/sample-video.mp4", MediaType.video.mp4, 1024)

            insertResult <- transaction { DoobieFileResourceDao.insert(fileResource) }
            _ = insertResult mustBe 1

            getByIdResult <- transaction { DoobieFileResourceDao.getById(fileResource.id) }
            _ = getByIdResult mustBe Some(fileResource)

            getByIdNoResult <- transaction { DoobieFileResourceDao.getById("random") }
            _ = getByIdNoResult mustBe None

            findByPathResult <- transaction { DoobieFileResourceDao.findByPath(fileResource.path) }
            _ = findByPathResult mustBe Some(fileResource)

            findByPathNoResult <- transaction { DoobieFileResourceDao.findByPath("/opt/missing.mp4") }
            _ = findByPathNoResult mustBe None

            deletionResult <- transaction { DoobieFileResourceDao.deleteById(fileResource.id) }
            _ = deletionResult mustBe 1

            emptyGetById <- transaction { DoobieFileResourceDao.getById(fileResource.id) }
            _ = emptyGetById mustBe None

            emptyFindByPath <- transaction { DoobieFileResourceDao.findByPath(fileResource.path) }
            _ = emptyFindByPath mustBe None

            emptyDeleteResult <- transaction { DoobieFileResourceDao.deleteById(fileResource.id) }
            _ = emptyDeleteResult mustBe 0
          }
          yield insertResult
      }

      }
}
