package com.ruchij.core.daos.resource

import cats.effect.IO
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.test.{H2TestDatabase, PostgresTestDatabase, TestDatabase}
import com.ruchij.core.types.Clock
import org.http4s.MediaType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers


class H2DoobieFileResourceDaoSpec extends DoobieFileResourceDaoSpec with H2TestDatabase

class PostgresDoobieFileResourceDaoSpec extends DoobieFileResourceDaoSpec with PostgresTestDatabase

abstract class DoobieFileResourceDaoSpec extends AnyFlatSpec with Matchers with TestDatabase {

  "DoobieFileResource" should "perform CRUD operation" in runIO {
    transactor
      .use {
        transaction =>
          for {
            timestamp <- Clock[IO].timestamp
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

  "update" should "update file resource size" in runIO {
    transactor
      .use { transaction =>
        for {
          timestamp <- Clock[IO].timestamp
          fileResource = FileResource("file-update-test", timestamp, "/video/update-test.mp4", MediaType.video.mp4, 1024)

          _ <- transaction { DoobieFileResourceDao.insert(fileResource) }

          updateResult <- transaction { DoobieFileResourceDao.update(fileResource.id, 2048) }
          _ <- IO.delay { updateResult mustBe 1 }

          updatedResource <- transaction { DoobieFileResourceDao.getById(fileResource.id) }
          _ <- IO.delay {
            updatedResource mustBe defined
            updatedResource.get.size mustBe 2048
            updatedResource.get.path mustBe fileResource.path
          }
        } yield ()
      }
  }

  it should "return 0 when updating non-existent resource" in runIO {
    transactor
      .use { transaction =>
        for {
          updateResult <- transaction { DoobieFileResourceDao.update("non-existent-id", 9999) }
          _ <- IO.delay { updateResult mustBe 0 }
        } yield ()
      }
  }
}
