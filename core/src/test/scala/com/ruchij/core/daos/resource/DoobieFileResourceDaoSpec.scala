package com.ruchij.core.daos.resource

import cats.effect.IO
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.test.DoobieProvider
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.Providers._
import com.ruchij.core.types.JodaClock
import org.http4s.MediaType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global

class DoobieFileResourceDaoSpec extends AnyFlatSpec with Matchers {

  "DoobieFileResource" should "perform CRUD operation" in runIO {
    DoobieProvider.inMemoryTransactor[IO]
      .use {
        transaction =>
          for {
            timestamp <- JodaClock[IO].timestamp
            fileResource = FileResource("file-id", timestamp, "/video/sample-video.mp4", MediaType.video.mp4, 1024)

            insertResult <- transaction { DoobieFileResourceDao.insert(fileResource) }
            _ <- IO.delay { insertResult mustBe 1 }

            getByIdResult <- transaction { DoobieFileResourceDao.getById(fileResource.id) }
            _ <- IO.delay {  getByIdResult mustBe Some(fileResource) }

            getByIdNoResult <- transaction { DoobieFileResourceDao.getById("random") }
            _ <- IO.delay { getByIdNoResult mustBe None }

            findByPathResult <- transaction { DoobieFileResourceDao.findByPath(fileResource.path) }
            _ <- IO.delay { findByPathResult mustBe Some(fileResource) }

            findByPathNoResult <- transaction { DoobieFileResourceDao.findByPath("/opt/missing.mp4") }
            _ <- IO.delay { findByPathNoResult mustBe None }

            deletionResult <- transaction { DoobieFileResourceDao.deleteById(fileResource.id) }
            _ <- IO.delay { deletionResult mustBe 1 }

            emptyGetById <- transaction { DoobieFileResourceDao.getById(fileResource.id) }
            _ <- IO.delay { emptyGetById mustBe None }

            emptyFindByPath <- transaction { DoobieFileResourceDao.findByPath(fileResource.path) }
            _ <- IO.delay { emptyFindByPath mustBe None }

            emptyDeleteResult <- transaction { DoobieFileResourceDao.deleteById(fileResource.id) }
            _ <- IO.delay { emptyDeleteResult mustBe 0 }
          }
          yield insertResult
      }

      }
}
