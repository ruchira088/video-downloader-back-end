package com.ruchij.core.daos.videometadata

import cats.effect.IO
import cats.~>
import com.ruchij.core.daos.resource.DoobieFileResourceDao
import com.ruchij.core.daos.resource.models.FileResource
import com.ruchij.core.daos.videometadata.models.{CustomVideoSite, VideoMetadata}
import com.ruchij.core.external.embedded.EmbeddedCoreResourcesProvider
import com.ruchij.core.test.IOSupport.runIO
import com.ruchij.core.types.Clock
import doobie.ConnectionIO
import org.http4s.MediaType
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

class DoobieVideoMetadataDaoSpec extends AnyFlatSpec with Matchers with OptionValues {

  case class TestFixture(
    videoMetadata: VideoMetadata,
    thumbnailFileResource: FileResource,
    transaction: ConnectionIO ~> IO
  )

  def runTest(testFn: TestFixture => IO[Unit]): Unit =
    runIO {
      new EmbeddedCoreResourcesProvider[IO].transactor.use { transaction =>
        for {
          timestamp <- Clock[IO].timestamp

          thumbnailFileResource = FileResource(
            "thumbnail-id",
            timestamp,
            "/opt/image/thumbnail.jpg",
            MediaType.image.jpeg,
            1000
          )
          _ <- transaction(DoobieFileResourceDao.insert(thumbnailFileResource))

          videoMetadata = VideoMetadata(
            uri"https://spankbang.com/video/123",
            "video-metadata-id",
            CustomVideoSite.SpankBang,
            "Test Video Title",
            5 minutes,
            50000,
            thumbnailFileResource
          )
          _ <- transaction(DoobieVideoMetadataDao.insert(videoMetadata))

          result <- testFn(TestFixture(videoMetadata, thumbnailFileResource, transaction))
        } yield result
      }
    }

  "DoobieVideoMetadataDao" should "insert and retrieve video metadata by ID" in runTest { fixture =>
    for {
      maybeVideoMetadata <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        maybeVideoMetadata mustBe defined
        maybeVideoMetadata.value.id mustBe fixture.videoMetadata.id
        maybeVideoMetadata.value.url mustBe fixture.videoMetadata.url
        maybeVideoMetadata.value.videoSite mustBe fixture.videoMetadata.videoSite
        maybeVideoMetadata.value.title mustBe fixture.videoMetadata.title
        maybeVideoMetadata.value.duration mustBe fixture.videoMetadata.duration
        maybeVideoMetadata.value.size mustBe fixture.videoMetadata.size
        maybeVideoMetadata.value.thumbnail.id mustBe fixture.thumbnailFileResource.id
        maybeVideoMetadata.value.thumbnail.path mustBe fixture.thumbnailFileResource.path
        maybeVideoMetadata.value.thumbnail.mediaType mustBe fixture.thumbnailFileResource.mediaType
        maybeVideoMetadata.value.thumbnail.size mustBe fixture.thumbnailFileResource.size
      }
    } yield ()
  }

  it should "return None when video metadata is not found by ID" in runTest { fixture =>
    for {
      maybeVideoMetadata <- fixture.transaction(DoobieVideoMetadataDao.findById("non-existent-id"))

      _ <- IO.delay {
        maybeVideoMetadata mustBe None
      }
    } yield ()
  }

  it should "find video metadata by URL" in runTest { fixture =>
    for {
      maybeVideoMetadata <- fixture.transaction(DoobieVideoMetadataDao.findByUrl(fixture.videoMetadata.url))

      _ <- IO.delay {
        maybeVideoMetadata mustBe defined
        maybeVideoMetadata.value.id mustBe fixture.videoMetadata.id
        maybeVideoMetadata.value.url mustBe fixture.videoMetadata.url
        maybeVideoMetadata.value.title mustBe fixture.videoMetadata.title
      }
    } yield ()
  }

  it should "return None when video metadata is not found by URL" in runTest { fixture =>
    for {
      maybeVideoMetadata <- fixture.transaction(DoobieVideoMetadataDao.findByUrl(uri"https://example.com/nonexistent"))

      _ <- IO.delay {
        maybeVideoMetadata mustBe None
      }
    } yield ()
  }

  it should "update video metadata title" in runTest { fixture =>
    val newTitle = "Updated Video Title"

    for {
      updateCount <- fixture.transaction(
        DoobieVideoMetadataDao.update(fixture.videoMetadata.id, Some(newTitle), None, None)
      )

      maybeUpdated <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        updateCount mustBe 1
        maybeUpdated.value.title mustBe newTitle
        maybeUpdated.value.size mustBe fixture.videoMetadata.size
        maybeUpdated.value.duration mustBe fixture.videoMetadata.duration
      }
    } yield ()
  }

  it should "update video metadata size" in runTest { fixture =>
    val newSize = 100000L

    for {
      updateCount <- fixture.transaction(
        DoobieVideoMetadataDao.update(fixture.videoMetadata.id, None, Some(newSize), None)
      )

      maybeUpdated <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        updateCount mustBe 1
        maybeUpdated.value.size mustBe newSize
        maybeUpdated.value.title mustBe fixture.videoMetadata.title
        maybeUpdated.value.duration mustBe fixture.videoMetadata.duration
      }
    } yield ()
  }

  it should "update video metadata duration" in runTest { fixture =>
    val newDuration = 10 minutes

    for {
      updateCount <- fixture.transaction(
        DoobieVideoMetadataDao.update(fixture.videoMetadata.id, None, None, Some(newDuration))
      )

      maybeUpdated <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        updateCount mustBe 1
        maybeUpdated.value.duration mustBe newDuration
        maybeUpdated.value.title mustBe fixture.videoMetadata.title
        maybeUpdated.value.size mustBe fixture.videoMetadata.size
      }
    } yield ()
  }

  it should "update multiple fields at once" in runTest { fixture =>
    val newTitle = "Completely New Title"
    val newSize = 200000L
    val newDuration = 15 minutes

    for {
      updateCount <- fixture.transaction(
        DoobieVideoMetadataDao.update(fixture.videoMetadata.id, Some(newTitle), Some(newSize), Some(newDuration))
      )

      maybeUpdated <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        updateCount mustBe 1
        maybeUpdated.value.title mustBe newTitle
        maybeUpdated.value.size mustBe newSize
        maybeUpdated.value.duration mustBe newDuration
      }
    } yield ()
  }

  it should "return 0 when updating with no fields provided" in runTest { fixture =>
    for {
      updateCount <- fixture.transaction(
        DoobieVideoMetadataDao.update(fixture.videoMetadata.id, None, None, None)
      )

      maybeUnchanged <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        updateCount mustBe 0
        maybeUnchanged.value.title mustBe fixture.videoMetadata.title
        maybeUnchanged.value.size mustBe fixture.videoMetadata.size
        maybeUnchanged.value.duration mustBe fixture.videoMetadata.duration
      }
    } yield ()
  }

  it should "return 0 when updating non-existent video metadata" in runTest { fixture =>
    for {
      updateCount <- fixture.transaction(
        DoobieVideoMetadataDao.update("non-existent-id", Some("New Title"), None, None)
      )

      _ <- IO.delay {
        updateCount mustBe 0
      }
    } yield ()
  }

  it should "delete video metadata by ID" in runTest { fixture =>
    for {
      deleteCount <- fixture.transaction(DoobieVideoMetadataDao.deleteById(fixture.videoMetadata.id))

      maybeDeleted <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        deleteCount mustBe 1
        maybeDeleted mustBe None
      }
    } yield ()
  }

  it should "return 0 when deleting non-existent video metadata" in runTest { fixture =>
    for {
      deleteCount <- fixture.transaction(DoobieVideoMetadataDao.deleteById("non-existent-id"))

      _ <- IO.delay {
        deleteCount mustBe 0
      }
    } yield ()
  }

  it should "return 0 when deleting already deleted video metadata" in runTest { fixture =>
    for {
      firstDeleteCount <- fixture.transaction(DoobieVideoMetadataDao.deleteById(fixture.videoMetadata.id))
      secondDeleteCount <- fixture.transaction(DoobieVideoMetadataDao.deleteById(fixture.videoMetadata.id))

      _ <- IO.delay {
        firstDeleteCount mustBe 1
        secondDeleteCount mustBe 0
      }
    } yield ()
  }

  it should "insert multiple video metadata records" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp

      thumbnailFileResource2 = FileResource(
        "thumbnail-id-2",
        timestamp,
        "/opt/image/thumbnail2.jpg",
        MediaType.image.jpeg,
        2000
      )
      _ <- fixture.transaction(DoobieFileResourceDao.insert(thumbnailFileResource2))

      videoMetadata2 = VideoMetadata(
        uri"https://pornone.com/video/456",
        "video-metadata-id-2",
        CustomVideoSite.PornOne,
        "Second Video Title",
        10 minutes,
        100000,
        thumbnailFileResource2
      )
      insertCount <- fixture.transaction(DoobieVideoMetadataDao.insert(videoMetadata2))

      maybeFirst <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))
      maybeSecond <- fixture.transaction(DoobieVideoMetadataDao.findById(videoMetadata2.id))

      _ <- IO.delay {
        insertCount mustBe 1
        maybeFirst mustBe defined
        maybeSecond mustBe defined
        maybeFirst.value.id mustBe fixture.videoMetadata.id
        maybeSecond.value.id mustBe videoMetadata2.id
        maybeSecond.value.videoSite mustBe CustomVideoSite.PornOne
      }
    } yield ()
  }

  it should "find video metadata by different URLs" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp

      thumbnailFileResource2 = FileResource(
        "thumbnail-id-2",
        timestamp,
        "/opt/image/thumbnail2.jpg",
        MediaType.image.jpeg,
        2000
      )
      _ <- fixture.transaction(DoobieFileResourceDao.insert(thumbnailFileResource2))

      url2 = uri"https://xfreehd.com/video/789"
      videoMetadata2 = VideoMetadata(
        url2,
        "video-metadata-id-2",
        CustomVideoSite.XFreeHD,
        "Another Video",
        8 minutes,
        75000,
        thumbnailFileResource2
      )
      _ <- fixture.transaction(DoobieVideoMetadataDao.insert(videoMetadata2))

      maybeByUrl1 <- fixture.transaction(DoobieVideoMetadataDao.findByUrl(fixture.videoMetadata.url))
      maybeByUrl2 <- fixture.transaction(DoobieVideoMetadataDao.findByUrl(url2))

      _ <- IO.delay {
        maybeByUrl1.value.id mustBe fixture.videoMetadata.id
        maybeByUrl2.value.id mustBe videoMetadata2.id
      }
    } yield ()
  }

  it should "handle video metadata with different video sites" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp

      // Test PornOne
      thumbnailPornOne = FileResource("thumbnail-pornone", timestamp, "/opt/image/pornone.jpg", MediaType.image.jpeg, 500)
      _ <- fixture.transaction(DoobieFileResourceDao.insert(thumbnailPornOne))
      videoMetadataPornOne = VideoMetadata(uri"https://pornone.com/test", "id-pornone", CustomVideoSite.PornOne, "PornOne Video", 3 minutes, 30000, thumbnailPornOne)
      _ <- fixture.transaction(DoobieVideoMetadataDao.insert(videoMetadataPornOne))

      // Test FreshPorno
      thumbnailFreshPorno = FileResource("thumbnail-freshporno", timestamp, "/opt/image/freshporno.jpg", MediaType.image.jpeg, 600)
      _ <- fixture.transaction(DoobieFileResourceDao.insert(thumbnailFreshPorno))
      videoMetadataFreshPorno = VideoMetadata(uri"https://freshporno.net/test", "id-freshporno", CustomVideoSite.FreshPorno, "FreshPorno Video", 4 minutes, 40000, thumbnailFreshPorno)
      _ <- fixture.transaction(DoobieVideoMetadataDao.insert(videoMetadataFreshPorno))

      // Test TXXX
      thumbnailTxxx = FileResource("thumbnail-txxx", timestamp, "/opt/image/txxx.jpg", MediaType.image.jpeg, 700)
      _ <- fixture.transaction(DoobieFileResourceDao.insert(thumbnailTxxx))
      videoMetadataTxxx = VideoMetadata(uri"https://txxx.com/test", "id-txxx", CustomVideoSite.TXXX, "TXXX Video", 6 minutes, 60000, thumbnailTxxx)
      _ <- fixture.transaction(DoobieVideoMetadataDao.insert(videoMetadataTxxx))

      // Verify all are retrievable
      maybePornOne <- fixture.transaction(DoobieVideoMetadataDao.findById("id-pornone"))
      maybeFreshPorno <- fixture.transaction(DoobieVideoMetadataDao.findById("id-freshporno"))
      maybeTxxx <- fixture.transaction(DoobieVideoMetadataDao.findById("id-txxx"))

      _ <- IO.delay {
        maybePornOne.value.videoSite mustBe CustomVideoSite.PornOne
        maybeFreshPorno.value.videoSite mustBe CustomVideoSite.FreshPorno
        maybeTxxx.value.videoSite mustBe CustomVideoSite.TXXX
      }
    } yield ()
  }

  it should "update title only and preserve other fields" in runTest { fixture =>
    val originalSize = fixture.videoMetadata.size
    val originalDuration = fixture.videoMetadata.duration
    val newTitle = "Title Only Update"

    for {
      _ <- fixture.transaction(DoobieVideoMetadataDao.update(fixture.videoMetadata.id, Some(newTitle), None, None))
      maybeUpdated <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        maybeUpdated.value.title mustBe newTitle
        maybeUpdated.value.size mustBe originalSize
        maybeUpdated.value.duration mustBe originalDuration
        maybeUpdated.value.url mustBe fixture.videoMetadata.url
        maybeUpdated.value.videoSite mustBe fixture.videoMetadata.videoSite
      }
    } yield ()
  }

  it should "update size only and preserve other fields" in runTest { fixture =>
    val originalTitle = fixture.videoMetadata.title
    val originalDuration = fixture.videoMetadata.duration
    val newSize = 999999L

    for {
      _ <- fixture.transaction(DoobieVideoMetadataDao.update(fixture.videoMetadata.id, None, Some(newSize), None))
      maybeUpdated <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        maybeUpdated.value.title mustBe originalTitle
        maybeUpdated.value.size mustBe newSize
        maybeUpdated.value.duration mustBe originalDuration
      }
    } yield ()
  }

  it should "update duration only and preserve other fields" in runTest { fixture =>
    val originalTitle = fixture.videoMetadata.title
    val originalSize = fixture.videoMetadata.size
    val newDuration = 30 minutes

    for {
      _ <- fixture.transaction(DoobieVideoMetadataDao.update(fixture.videoMetadata.id, None, None, Some(newDuration)))
      maybeUpdated <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        maybeUpdated.value.title mustBe originalTitle
        maybeUpdated.value.size mustBe originalSize
        maybeUpdated.value.duration mustBe newDuration
      }
    } yield ()
  }

  it should "update title and size together" in runTest { fixture =>
    val newTitle = "Title and Size Update"
    val newSize = 123456L

    for {
      updateCount <- fixture.transaction(
        DoobieVideoMetadataDao.update(fixture.videoMetadata.id, Some(newTitle), Some(newSize), None)
      )
      maybeUpdated <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        updateCount mustBe 1
        maybeUpdated.value.title mustBe newTitle
        maybeUpdated.value.size mustBe newSize
        maybeUpdated.value.duration mustBe fixture.videoMetadata.duration
      }
    } yield ()
  }

  it should "update title and duration together" in runTest { fixture =>
    val newTitle = "Title and Duration Update"
    val newDuration = 20 minutes

    for {
      updateCount <- fixture.transaction(
        DoobieVideoMetadataDao.update(fixture.videoMetadata.id, Some(newTitle), None, Some(newDuration))
      )
      maybeUpdated <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        updateCount mustBe 1
        maybeUpdated.value.title mustBe newTitle
        maybeUpdated.value.size mustBe fixture.videoMetadata.size
        maybeUpdated.value.duration mustBe newDuration
      }
    } yield ()
  }

  it should "update size and duration together" in runTest { fixture =>
    val newSize = 654321L
    val newDuration = 25 minutes

    for {
      updateCount <- fixture.transaction(
        DoobieVideoMetadataDao.update(fixture.videoMetadata.id, None, Some(newSize), Some(newDuration))
      )
      maybeUpdated <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        updateCount mustBe 1
        maybeUpdated.value.title mustBe fixture.videoMetadata.title
        maybeUpdated.value.size mustBe newSize
        maybeUpdated.value.duration mustBe newDuration
      }
    } yield ()
  }

  it should "handle edge case with zero duration" in runTest { fixture =>
    val zeroDuration = 0 seconds

    for {
      updateCount <- fixture.transaction(
        DoobieVideoMetadataDao.update(fixture.videoMetadata.id, None, None, Some(zeroDuration))
      )
      maybeUpdated <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        updateCount mustBe 1
        maybeUpdated.value.duration mustBe zeroDuration
      }
    } yield ()
  }

  it should "handle edge case with zero size" in runTest { fixture =>
    val zeroSize = 0L

    for {
      updateCount <- fixture.transaction(
        DoobieVideoMetadataDao.update(fixture.videoMetadata.id, None, Some(zeroSize), None)
      )
      maybeUpdated <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        updateCount mustBe 1
        maybeUpdated.value.size mustBe zeroSize
      }
    } yield ()
  }

  it should "handle edge case with empty title" in runTest { fixture =>
    val emptyTitle = ""

    for {
      updateCount <- fixture.transaction(
        DoobieVideoMetadataDao.update(fixture.videoMetadata.id, Some(emptyTitle), None, None)
      )
      maybeUpdated <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        updateCount mustBe 1
        maybeUpdated.value.title mustBe emptyTitle
      }
    } yield ()
  }

  it should "handle video metadata with long title" in runTest { fixture =>
    val longTitle = "A" * 500

    for {
      updateCount <- fixture.transaction(
        DoobieVideoMetadataDao.update(fixture.videoMetadata.id, Some(longTitle), None, None)
      )
      maybeUpdated <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        updateCount mustBe 1
        maybeUpdated.value.title mustBe longTitle
      }
    } yield ()
  }

  it should "handle video metadata with special characters in title" in runTest { fixture =>
    val specialTitle = "Video with 'quotes' and \"double quotes\" & ampersands <brackets>"

    for {
      updateCount <- fixture.transaction(
        DoobieVideoMetadataDao.update(fixture.videoMetadata.id, Some(specialTitle), None, None)
      )
      maybeUpdated <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        updateCount mustBe 1
        maybeUpdated.value.title mustBe specialTitle
      }
    } yield ()
  }

  it should "handle video metadata with unicode characters in title" in runTest { fixture =>
    val unicodeTitle = "Video with unicode characters"

    for {
      updateCount <- fixture.transaction(
        DoobieVideoMetadataDao.update(fixture.videoMetadata.id, Some(unicodeTitle), None, None)
      )
      maybeUpdated <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        updateCount mustBe 1
        maybeUpdated.value.title mustBe unicodeTitle
      }
    } yield ()
  }

  it should "handle large size values" in runTest { fixture =>
    val largeSize = Long.MaxValue

    for {
      updateCount <- fixture.transaction(
        DoobieVideoMetadataDao.update(fixture.videoMetadata.id, None, Some(largeSize), None)
      )
      maybeUpdated <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        updateCount mustBe 1
        maybeUpdated.value.size mustBe largeSize
      }
    } yield ()
  }

  it should "handle large duration values" in runTest { fixture =>
    val largeDuration = 24 hours

    for {
      updateCount <- fixture.transaction(
        DoobieVideoMetadataDao.update(fixture.videoMetadata.id, None, None, Some(largeDuration))
      )
      maybeUpdated <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        updateCount mustBe 1
        maybeUpdated.value.duration mustBe largeDuration
      }
    } yield ()
  }

  it should "preserve thumbnail information after update" in runTest { fixture =>
    val newTitle = "Updated but thumbnail unchanged"

    for {
      _ <- fixture.transaction(DoobieVideoMetadataDao.update(fixture.videoMetadata.id, Some(newTitle), None, None))
      maybeUpdated <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        maybeUpdated.value.thumbnail.id mustBe fixture.thumbnailFileResource.id
        maybeUpdated.value.thumbnail.path mustBe fixture.thumbnailFileResource.path
        maybeUpdated.value.thumbnail.mediaType mustBe fixture.thumbnailFileResource.mediaType
        maybeUpdated.value.thumbnail.size mustBe fixture.thumbnailFileResource.size
      }
    } yield ()
  }

  it should "preserve URL and video site after update" in runTest { fixture =>
    val newTitle = "Updated title"
    val newSize = 111111L
    val newDuration = 7 minutes

    for {
      _ <- fixture.transaction(
        DoobieVideoMetadataDao.update(fixture.videoMetadata.id, Some(newTitle), Some(newSize), Some(newDuration))
      )
      maybeUpdated <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        maybeUpdated.value.url mustBe fixture.videoMetadata.url
        maybeUpdated.value.videoSite mustBe fixture.videoMetadata.videoSite
      }
    } yield ()
  }

  it should "insert video metadata and return correct insert count" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp

      thumbnailNew = FileResource("thumbnail-new", timestamp, "/opt/image/new.jpg", MediaType.image.jpeg, 800)
      _ <- fixture.transaction(DoobieFileResourceDao.insert(thumbnailNew))

      newVideoMetadata = VideoMetadata(
        uri"https://hclips.com/video/new",
        "new-video-id",
        CustomVideoSite.HClips,
        "New Video",
        12 minutes,
        120000,
        thumbnailNew
      )
      insertCount <- fixture.transaction(DoobieVideoMetadataDao.insert(newVideoMetadata))

      _ <- IO.delay {
        insertCount mustBe 1
      }
    } yield ()
  }

  it should "handle UPornia video site" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp

      thumbnailUpornia = FileResource("thumbnail-upornia", timestamp, "/opt/image/upornia.jpg", MediaType.image.jpeg, 900)
      _ <- fixture.transaction(DoobieFileResourceDao.insert(thumbnailUpornia))

      uporniaMetadata = VideoMetadata(
        uri"https://upornia.com/video/test",
        "upornia-id",
        CustomVideoSite.UPornia,
        "UPornia Video",
        9 minutes,
        90000,
        thumbnailUpornia
      )
      _ <- fixture.transaction(DoobieVideoMetadataDao.insert(uporniaMetadata))

      maybeUpornia <- fixture.transaction(DoobieVideoMetadataDao.findById("upornia-id"))

      _ <- IO.delay {
        maybeUpornia.value.videoSite mustBe CustomVideoSite.UPornia
      }
    } yield ()
  }

  it should "handle HotMovs video site" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp

      thumbnailHotmovs = FileResource("thumbnail-hotmovs", timestamp, "/opt/image/hotmovs.jpg", MediaType.image.jpeg, 950)
      _ <- fixture.transaction(DoobieFileResourceDao.insert(thumbnailHotmovs))

      hotmovsMetadata = VideoMetadata(
        uri"https://hotmovs.com/video/test",
        "hotmovs-id",
        CustomVideoSite.HotMovs,
        "HotMovs Video",
        11 minutes,
        110000,
        thumbnailHotmovs
      )
      _ <- fixture.transaction(DoobieVideoMetadataDao.insert(hotmovsMetadata))

      maybeHotmovs <- fixture.transaction(DoobieVideoMetadataDao.findById("hotmovs-id"))

      _ <- IO.delay {
        maybeHotmovs.value.videoSite mustBe CustomVideoSite.HotMovs
      }
    } yield ()
  }

  it should "handle HdZog video site" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp

      thumbnailHdzog = FileResource("thumbnail-hdzog", timestamp, "/opt/image/hdzog.jpg", MediaType.image.jpeg, 980)
      _ <- fixture.transaction(DoobieFileResourceDao.insert(thumbnailHdzog))

      hdzogMetadata = VideoMetadata(
        uri"https://hdzog.com/video/test",
        "hdzog-id",
        CustomVideoSite.HdZog,
        "HdZog Video",
        13 minutes,
        130000,
        thumbnailHdzog
      )
      _ <- fixture.transaction(DoobieVideoMetadataDao.insert(hdzogMetadata))

      maybeHdzog <- fixture.transaction(DoobieVideoMetadataDao.findById("hdzog-id"))

      _ <- IO.delay {
        maybeHdzog.value.videoSite mustBe CustomVideoSite.HdZog
      }
    } yield ()
  }

  it should "handle SxyPrn video site" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp

      thumbnailSxyprn = FileResource("thumbnail-sxyprn", timestamp, "/opt/image/sxyprn.jpg", MediaType.image.jpeg, 1100)
      _ <- fixture.transaction(DoobieFileResourceDao.insert(thumbnailSxyprn))

      sxyprnMetadata = VideoMetadata(
        uri"https://sxyprn.com/video/test",
        "sxyprn-id",
        CustomVideoSite.SxyPrn,
        "SxyPrn Video",
        14 minutes,
        140000,
        thumbnailSxyprn
      )
      _ <- fixture.transaction(DoobieVideoMetadataDao.insert(sxyprnMetadata))

      maybeSxyprn <- fixture.transaction(DoobieVideoMetadataDao.findById("sxyprn-id"))

      _ <- IO.delay {
        maybeSxyprn.value.videoSite mustBe CustomVideoSite.SxyPrn
      }
    } yield ()
  }

  it should "correctly retrieve thumbnail createdAt timestamp" in runTest { fixture =>
    for {
      maybeVideoMetadata <- fixture.transaction(DoobieVideoMetadataDao.findById(fixture.videoMetadata.id))

      _ <- IO.delay {
        maybeVideoMetadata.value.thumbnail.createdAt.toEpochMilli mustBe fixture.thumbnailFileResource.createdAt.toEpochMilli
      }
    } yield ()
  }

  it should "find video metadata by URL with query parameters" in runTest { fixture =>
    for {
      timestamp <- Clock[IO].timestamp

      thumbnailWithQuery = FileResource("thumbnail-query", timestamp, "/opt/image/query.jpg", MediaType.image.jpeg, 1200)
      _ <- fixture.transaction(DoobieFileResourceDao.insert(thumbnailWithQuery))

      urlWithQuery = uri"https://example.com/video?id=123&quality=hd"
      metadataWithQuery = VideoMetadata(
        urlWithQuery,
        "query-video-id",
        CustomVideoSite.SpankBang,
        "Video with Query Params",
        5 minutes,
        50000,
        thumbnailWithQuery
      )
      _ <- fixture.transaction(DoobieVideoMetadataDao.insert(metadataWithQuery))

      maybeFound <- fixture.transaction(DoobieVideoMetadataDao.findByUrl(urlWithQuery))
      maybeNotFound <- fixture.transaction(DoobieVideoMetadataDao.findByUrl(uri"https://example.com/video?id=123"))

      _ <- IO.delay {
        maybeFound mustBe defined
        maybeFound.value.id mustBe "query-video-id"
        maybeNotFound mustBe None
      }
    } yield ()
  }

  it should "return true for isThumbnailFileResource when file resource is a thumbnail" in runTest { fixture =>
    for {
      isThumbnail <- fixture.transaction(
        DoobieVideoMetadataDao.isThumbnailFileResource(fixture.thumbnailFileResource.id)
      )

      _ <- IO.delay {
        isThumbnail mustBe true
      }
    } yield ()
  }

  it should "return false for isThumbnailFileResource when file resource does not exist" in runTest { fixture =>
    for {
      isThumbnail <- fixture.transaction(
        DoobieVideoMetadataDao.isThumbnailFileResource("non-existent-file-resource")
      )

      _ <- IO.delay {
        isThumbnail mustBe false
      }
    } yield ()
  }
}
