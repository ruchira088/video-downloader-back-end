package com.ruchij.api.web.requests

import cats.effect.IO
import com.ruchij.core.test.IOSupport.runIO
import fs2.Stream
import org.http4s.MediaType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class FileAssetSpec extends AnyFlatSpec with Matchers {

  "FileAsset" should "store file name, media type, and data" in {
    val data = Stream.emits[IO, Byte]("test content".getBytes)
    val fileAsset = FileAsset[IO]("test.png", MediaType.image.png, data)

    fileAsset.fileName mustBe "test.png"
    fileAsset.mediaType mustBe MediaType.image.png
  }

  it should "preserve all properties after creation" in runIO {
    val testData = "This is test image data"
    val data = Stream.emits[IO, Byte](testData.getBytes)
    val fileAsset = FileAsset[IO]("photo.jpg", MediaType.image.jpeg, data)

    fileAsset.data.through(fs2.text.utf8.decode).compile.string.map { content =>
      content mustBe testData
      fileAsset.fileName mustBe "photo.jpg"
      fileAsset.mediaType mustBe MediaType.image.jpeg
    }
  }

  it should "support different image types" in {
    val data = Stream.empty[IO]

    val pngAsset = FileAsset[IO]("image.png", MediaType.image.png, data)
    pngAsset.mediaType mustBe MediaType.image.png

    val jpegAsset = FileAsset[IO]("image.jpg", MediaType.image.jpeg, data)
    jpegAsset.mediaType mustBe MediaType.image.jpeg

    val gifAsset = FileAsset[IO]("image.gif", MediaType.image.gif, data)
    gifAsset.mediaType mustBe MediaType.image.gif

    val webpAsset = FileAsset[IO]("image.webp", MediaType.image.webp, data)
    webpAsset.mediaType mustBe MediaType.image.webp
  }

  it should "allow empty file names" in {
    val data = Stream.empty[IO]
    val asset = FileAsset[IO]("", MediaType.image.png, data)
    asset.fileName mustBe ""
  }

  it should "handle various file name formats" in {
    val data = Stream.empty[IO]

    val simpleAsset = FileAsset[IO]("test.png", MediaType.image.png, data)
    simpleAsset.fileName mustBe "test.png"

    val pathAsset = FileAsset[IO]("/path/to/image.png", MediaType.image.png, data)
    pathAsset.fileName mustBe "/path/to/image.png"

    val spaceAsset = FileAsset[IO]("my image file.png", MediaType.image.png, data)
    spaceAsset.fileName mustBe "my image file.png"
  }

  "fileAssetDecoder implicit" should "exist for FileAsset" in {
    val decoder = implicitly[org.http4s.EntityDecoder[IO, FileAsset[IO]]]
    decoder must not be null
  }
}
