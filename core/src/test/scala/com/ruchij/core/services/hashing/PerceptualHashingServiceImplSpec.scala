package com.ruchij.core.services.hashing

import cats.effect.IO
import com.ruchij.core.test.IOSupport.runIO
import fs2.Stream
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class PerceptualHashingServiceImplSpec extends AnyFlatSpec with Matchers {

  private val hashingService = new PerceptualHashingServiceImpl[IO]

  private def createSolidImage(width: Int, height: Int, color: Color): Array[Byte] =
    toBytes(width, height) { (graphics, _, _) =>
      graphics.setColor(color)
      graphics.fillRect(0, 0, width, height)
    }

  private def createHorizontalStripes(width: Int, height: Int): Array[Byte] =
    toBytes(width, height) { (graphics, w, h) =>
      val stripeHeight = h / 8
      for (i <- 0 until 8) {
        graphics.setColor(if (i % 2 == 0) Color.BLACK else Color.WHITE)
        graphics.fillRect(0, i * stripeHeight, w, stripeHeight)
      }
    }

  private def createCheckerboard(width: Int, height: Int, squareSize: Int): Array[Byte] =
    toBytes(width, height) { (graphics, w, h) =>
      for {
        x <- 0 until w by squareSize
        y <- 0 until h by squareSize
      } {
        graphics.setColor(if (((x / squareSize) + (y / squareSize)) % 2 == 0) Color.BLACK else Color.WHITE)
        graphics.fillRect(x, y, squareSize, squareSize)
      }
    }

  private def toBytes(width: Int, height: Int)(draw: (java.awt.Graphics2D, Int, Int) => Unit): Array[Byte] = {
    val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    draw(graphics, width, height)
    graphics.dispose()

    val outputStream = new ByteArrayOutputStream()
    ImageIO.write(image, "png", outputStream)
    outputStream.toByteArray
  }

  private def imageStream(bytes: Array[Byte]): Stream[IO, Byte] =
    Stream.emits(bytes)

  "hashImage" should "produce a consistent hash for the same image" in runIO {
    val image = createHorizontalStripes(200, 200)

    for {
      hash1 <- hashingService.hashImage(imageStream(image))
      hash2 <- hashingService.hashImage(imageStream(image))
    } yield hash1 mustBe hash2
  }

  it should "produce different hashes for structurally different images" in runIO {
    val stripes = createHorizontalStripes(200, 200)
    val checkerboard = createCheckerboard(200, 200, 25)

    for {
      stripesHash <- hashingService.hashImage(imageStream(stripes))
      checkerboardHash <- hashingService.hashImage(imageStream(checkerboard))
    } yield stripesHash must not be checkerboardHash
  }

  it should "produce the same hash regardless of image dimensions for the same pattern" in runIO {
    val smallImage = createSolidImage(64, 64, Color.GREEN)
    val largeImage = createSolidImage(512, 512, Color.GREEN)

    for {
      smallHash <- hashingService.hashImage(imageStream(smallImage))
      largeHash <- hashingService.hashImage(imageStream(largeImage))
    } yield smallHash mustBe largeHash
  }

  "compareHashes" should "return 0.0 for identical hashes" in runIO {
    val image = createCheckerboard(200, 200, 25)

    for {
      hash <- hashingService.hashImage(imageStream(image))
      distance <- hashingService.compareHashes(hash, hash)
    } yield distance mustBe 0.0
  }

  it should "return a low distance for similar images" in runIO {
    val stripes = createHorizontalStripes(200, 200)
    val checkerboard = createCheckerboard(200, 200, 40)

    for {
      hash1 <- hashingService.hashImage(imageStream(stripes))
      hash2 <- hashingService.hashImage(imageStream(checkerboard))
      distance <- hashingService.compareHashes(hash1, hash2)
    } yield {
      distance must be > 0.1
      distance must be < 0.2
    }
  }

  it should "return a high distance for structurally different images" in runIO {
    val fineCheckerboard = createCheckerboard(200, 200, 20)
    val coarseCheckerboard = createCheckerboard(200, 200, 30)

    for {
      fineHash <- hashingService.hashImage(imageStream(fineCheckerboard))
      coarseHash <- hashingService.hashImage(imageStream(coarseCheckerboard))
      distance <- hashingService.compareHashes(fineHash, coarseHash)
    } yield distance must be > 0.3
  }
}
