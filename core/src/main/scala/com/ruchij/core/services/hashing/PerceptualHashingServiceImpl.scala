package com.ruchij.core.services.hashing

import cats.Applicative
import cats.effect.kernel.Sync
import cats.implicits._
import com.ruchij.core.services.hashing.PerceptualHashingServiceImpl.BitResolution
import dev.brachtendorf.jimagehash.hash.Hash
import dev.brachtendorf.jimagehash.hashAlgorithms.PerceptiveHash
import fs2.Stream

import javax.imageio.ImageIO

class PerceptualHashingServiceImpl[F[_]: Sync] extends PerceptualHashingService[F] {
  override def hashImage(image: Stream[F, Byte]): F[BigInt] =
    for {
      perceptiveHash <- Applicative[F].pure(new PerceptiveHash(BitResolution))
      bytes <- image.compile.to(Array)
      bufferedImage <- Sync[F].blocking(ImageIO.read(new java.io.ByteArrayInputStream(bytes)))
      hash <- Sync[F].blocking(perceptiveHash.hash(bufferedImage))
    } yield hash.getHashValue

  override def compareHashes(hashA: BigInt, hashB: BigInt): F[Double] =
    for {
      perceptiveHash <- Applicative[F].pure(new PerceptiveHash(BitResolution))
      imageHashA = new Hash(hashA.bigInteger, BitResolution + 4, perceptiveHash.algorithmId())
      imageHashB = new Hash(hashB.bigInteger, BitResolution + 4, perceptiveHash.algorithmId())
      similarityScore <- Sync[F].blocking(imageHashA.normalizedHammingDistance(imageHashB))
    } yield similarityScore
}

object PerceptualHashingServiceImpl {
  private val BitResolution = 32
}
