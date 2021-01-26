package com.ruchij.core.daos.resource

import cats.effect.IO
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.test.{DoobieProvider, IOSupport}
import com.ruchij.core.test.Providers._
import com.ruchij.core.types.JodaClock
import org.http4s.MediaType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import com.ruchij.core.types.FunctionKTypes.transaction

import scala.concurrent.ExecutionContext.Implicits.global

class DoobieFileResourceDaoSpec extends AnyFlatSpec with Matchers with IOSupport {

  "DoobieFileResource" should "perform CRUD operation" in run {
    for {
      timestamp <- JodaClock[IO].timestamp
      fileResource = FileResource("file-id", timestamp, "/video/sample-video.mp4", MediaType.video.mp4, 1024)

      transactor <- DoobieProvider.h2InMemoryTransactor[IO]

      insertResult <- transaction(transactor).apply { DoobieFileResourceDao.insert(fileResource) }
      _ = insertResult mustBe 1

      getByIdResult <- transaction(transactor).apply { DoobieFileResourceDao.getById(fileResource.id) }
      _ = getByIdResult mustBe Some(fileResource)

      getByIdNoResult <- transaction(transactor).apply { DoobieFileResourceDao.getById("random") }
      _ = getByIdNoResult mustBe None

      findByPathResult <- transaction(transactor).apply { DoobieFileResourceDao.findByPath(fileResource.path) }
      _ = findByPathResult mustBe Some(fileResource)

      findByPathNoResult <- transaction(transactor).apply { DoobieFileResourceDao.findByPath("/opt/missing.mp4") }
      _ = findByPathNoResult mustBe None

      deletionResult <- transaction(transactor).apply { DoobieFileResourceDao.deleteById(fileResource.id) }
      _ = deletionResult mustBe 1

      emptyGetById <- transaction(transactor).apply { DoobieFileResourceDao.getById(fileResource.id) }
      _ = emptyGetById mustBe None

      emptyFindByPath <- transaction(transactor).apply { DoobieFileResourceDao.findByPath(fileResource.path) }
      _ = emptyFindByPath mustBe None

      emptyDeleteResult <- transaction(transactor).apply { DoobieFileResourceDao.deleteById(fileResource.id) }
      _ = emptyDeleteResult mustBe 0
    }
    yield insertResult
  }

}
